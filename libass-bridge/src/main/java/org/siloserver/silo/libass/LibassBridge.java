package org.siloserver.silo.libass;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.NoSampleRenderer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.ui.SubtitleView;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import io.github.peerless2012.ass.Ass;
import io.github.peerless2012.ass.AssRender;
import io.github.peerless2012.ass.media.AssHandler;
import io.github.peerless2012.ass.media.AssHandlerConfig;
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor;
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory;
import io.github.peerless2012.ass.media.type.AssRenderType;
import io.github.peerless2012.ass.media.widget.AssSubtitleView;

/**
 * Java boundary around ass-media/libass.
 *
 * ass-media is Kotlin 2.2 bytecode while Silo currently compiles Kotlin 2.1.
 * Keeping all ass-media symbols private to this Java module avoids forcing a
 * repo-wide compiler and KSP upgrade. Callers see only Media3 and Java types.
 */
@UnstableApi
public final class LibassBridge {
    // Media3 offsets renderer positions by 10^12us. ass-media's own renderer
    // subtracts the same value before asking libass for the active frame.
    private static final long MEDIA3_RENDERER_POSITION_OFFSET_US = 1_000_000_000_000L;
    private static final List<String> PACKAGED_ABIS = Arrays.asList(
            "arm64-v8a", "armeabi-v7a", "x86", "x86_64"
    );

    private final boolean renderingSupported;
    private final boolean embeddedFontsSupported;
    /**
     * Recycled per player — see {@link #initialize(ExoPlayer)}.
     *
     * ass-kt frees native memory only in {@code finalize()}, and embedded MKV
     * font attachments accumulate in the {@code ASS_Library} this handler owns:
     * anime and typeset releases carry 5-30 MB each, so a process-lifetime
     * handler grows without bound until a mid-episode decoder allocation fails
     * or the process is killed outright. Dropping the handler when the player it
     * belongs to is replaced makes the whole graph collectable, fonts included.
     *
     * Deliberately NOT {@code Ass.clearFont()}: libass permits
     * {@code ass_clear_fonts} only once every track and renderer on that library
     * is released, and AssHandler merely nulls its Kotlin references on a
     * media-item transition. Calling it is a use-after-free risk.
     */
    private final AssRenderType renderType;
    private volatile AssHandler handler;
    private volatile AssSubtitleParserFactory assFactory;
    private final SubtitleParser.Factory parserFactory;
    private WeakReference<AssSubtitleView> overlayRef = new WeakReference<>(null);
    private ExoPlayer initializedPlayer;

    private final Player.Listener frameSizeSyncListener = new Player.Listener() {
        @Override
        public void onTracksChanged(Tracks tracks) {
            syncOverlayFrameSizeLater();
        }
    };

    public LibassBridge(boolean preferOpenGl) {
        renderingSupported = probeNativeRuntime();
        embeddedFontsSupported = renderingSupported && probeMatroskaIntegration();
        if (renderingSupported) {
            AssRenderType renderType = preferOpenGl
                    ? AssRenderType.OVERLAY_OPEN_GL
                    : AssRenderType.OVERLAY_CANVAS;
            this.renderType = renderType;
            newHandler();
            parserFactory = buildParserFactory();
        } else {
            this.renderType = null;
            handler = null;
            assFactory = null;
            parserFactory = new DefaultSubtitleParserFactory();
        }
    }

    public boolean isRenderingSupported() {
        return renderingSupported;
    }

    public boolean isEmbeddedFontsSupported() {
        return embeddedFontsSupported;
    }

    public SubtitleParser.Factory getParserFactory() {
        return parserFactory;
    }

    private void newHandler() {
        handler = new AssHandler(renderType, new AssHandlerConfig());
        // Rebuilt with the handler: SiloPlayerFactory holds the wrappers around
        // this factory for the process lifetime, so a stale delegate here would
        // keep the previous handler — and its fonts — reachable forever, which
        // is the leak this recycling exists to close.
        assFactory = new AssSubtitleParserFactory(handler);
    }

    /**
     * Delegates every call to whichever {@link AssSubtitleParserFactory} belongs
     * to the current handler, so the long-lived wrappers in SiloPlayerFactory
     * never pin a retired one.
     */
    private SubtitleParser.Factory buildParserFactory() {
        SubtitleParser.Factory assFactory = new SubtitleParser.Factory() {
            @Override
            public boolean supportsFormat(Format format) {
                return LibassBridge.this.assFactory.supportsFormat(format);
            }

            @Override
            public int getCueReplacementBehavior(Format format) {
                return LibassBridge.this.assFactory.getCueReplacementBehavior(format);
            }

            @Override
            public SubtitleParser create(Format format) {
                return LibassBridge.this.assFactory.create(format);
            }
        };
        if (embeddedFontsSupported) return assFactory;

        // The ass-media Matroska extractor supplies both timed dialogue packets
        // and font attachments. If its Media3 reflection probe fails, do not
        // hand embedded MKV ASS to its no-op overlay parser or subtitles would
        // disappear. Preserve Media3's plain-text fallback while retaining
        // native libass rendering for independent sidecar ASS files.
        DefaultSubtitleParserFactory media3Factory = new DefaultSubtitleParserFactory();
        return new SubtitleParser.Factory() {
            @Override
            public boolean supportsFormat(Format format) {
                return assFactory.supportsFormat(format);
            }

            @Override
            public int getCueReplacementBehavior(Format format) {
                return assFactory.getCueReplacementBehavior(format);
            }

            @Override
            public SubtitleParser create(Format format) {
                boolean unsupportedEmbeddedAss = MimeTypes.TEXT_SSA.equals(format.sampleMimeType)
                        && MimeTypes.VIDEO_MATROSKA.equals(format.containerMimeType);
                return unsupportedEmbeddedAss
                        ? media3Factory.create(format)
                        : assFactory.create(format);
            }
        };
    }

    /**
     * Replaces Media3's Matroska extractor while retaining its position in the
     * extractor list. The replacement captures MKV font attachments and routes
     * ASS packets to the same libass handler as sidecar subtitles.
     */
    public ExtractorsFactory wrapExtractors(
            ExtractorsFactory delegate,
            SubtitleParser.Factory combinedParserFactory
    ) {
        if (!embeddedFontsSupported) return delegate;
        return new ExtractorsFactory() {
            @Override
            public Extractor[] createExtractors() {
                return replaceMatroska(delegate.createExtractors(), combinedParserFactory);
            }

            @Override
            public Extractor[] createExtractors(
                    Uri uri,
                    Map<String, List<String>> responseHeaders
            ) {
                return replaceMatroska(
                        delegate.createExtractors(uri, responseHeaders),
                        combinedParserFactory
                );
            }
        };
    }

    /** Adds a clock-only renderer that drives libass without consuming samples. */
    public RenderersFactory wrapRenderers(
            RenderersFactory delegate,
            LongSupplier subtitleOffsetUs
    ) {
        if (!renderingSupported) return delegate;
        return new RenderersFactory() {
            @Override
            public Renderer[] createRenderers(
                    Handler eventHandler,
                    VideoRendererEventListener videoListener,
                    AudioRendererEventListener audioListener,
                    TextOutput textOutput,
                    MetadataOutput metadataOutput
            ) {
                Renderer[] base = delegate.createRenderers(
                        eventHandler,
                        videoListener,
                        audioListener,
                        textOutput,
                        metadataOutput
                );
                Renderer[] result = Arrays.copyOf(base, base.length + 1);
                result[base.length] = new SiloLibassRenderer(handler, subtitleOffsetUs);
                return result;
            }

            @Override
            public Renderer createSecondaryRenderer(
                    Renderer renderer,
                    Handler eventHandler,
                    VideoRendererEventListener videoListener,
                    AudioRendererEventListener audioListener,
                    TextOutput textOutput,
                    MetadataOutput metadataOutput
            ) {
                return delegate.createSecondaryRenderer(
                        renderer,
                        eventHandler,
                        videoListener,
                        audioListener,
                        textOutput,
                        metadataOutput
                );
            }
        };
    }

    public void initialize(ExoPlayer player) {
        if (!renderingSupported || initializedPlayer == player) return;
        if (initializedPlayer != null) {
            initializedPlayer.removeListener(handler);
            initializedPlayer.removeListener(frameSizeSyncListener);
            // The retired player owns the renderers and extractors that hold the
            // old handler, and releasing it drops them. Everything else pointing
            // at that handler must go at the same moment or the fonts it
            // accumulated stay reachable and nothing is reclaimed.
            retireOverlay();
            newHandler();
        }
        initializedPlayer = player;
        handler.init(player);
        // Registered after AssHandler so its track update creates the render
        // before we correct the frame size to Silo's visible-video subtitle box.
        player.addListener(frameSizeSyncListener);
    }

    /** Adds one libass overlay beneath Media3's normal text cue layer. */
    public void attachTo(SubtitleView host) {
        if (!renderingSupported) return;
        AssSubtitleView overlay = null;
        for (int index = 0; index < host.getChildCount(); index++) {
            View child = host.getChildAt(index);
            if (child instanceof AssSubtitleView) {
                overlay = (AssSubtitleView) child;
                break;
            }
        }
        if (overlay == null) {
            overlay = new AssSubtitleView(host.getContext(), handler);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            // Index zero keeps Media3 text cues above libass if a malformed
            // stream exposes both representations at once.
            host.addView(overlay, 0, params);
            overlay.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> syncOverlayFrameSize());
        }
        overlayRef = new WeakReference<>(overlay);
        syncOverlayFrameSizeLater();
    }

    /**
     * Removes the overlay bound to the retiring handler.
     *
     * {@link #attachTo} reuses any AssSubtitleView already in the host, and that
     * view holds the handler it was built with — reusing it across a recycle
     * would render from a handler no player drives, and keep it alive.
     */
    private void retireOverlay() {
        AssSubtitleView overlay = overlayRef.get();
        overlayRef = new WeakReference<>(null);
        if (overlay == null) return;
        ViewGroup parent = overlay.getParent() instanceof ViewGroup
                ? (ViewGroup) overlay.getParent()
                : null;
        if (parent != null) parent.removeView(overlay);
    }

    private Extractor[] replaceMatroska(
            Extractor[] extractors,
            SubtitleParser.Factory combinedParserFactory
    ) {
        for (int index = 0; index < extractors.length; index++) {
            Extractor extractor = extractors[index];
            if (extractor instanceof MatroskaExtractor
                    && !(extractor instanceof AssMatroskaExtractor)) {
                extractors[index] = new AssMatroskaExtractor(combinedParserFactory, handler);
            }
        }
        return extractors;
    }

    private void syncOverlayFrameSizeLater() {
        AssSubtitleView overlay = overlayRef.get();
        if (overlay != null) overlay.post(this::syncOverlayFrameSize);
    }

    private void syncOverlayFrameSize() {
        AssSubtitleView overlay = overlayRef.get();
        AssRender render = handler == null ? null : handler.getRender();
        if (overlay != null && render != null && overlay.getWidth() > 0 && overlay.getHeight() > 0) {
            render.setFrameSize(overlay.getWidth(), overlay.getHeight());
        }
    }

    private static boolean probeNativeRuntime() {
        boolean packagedAbi = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if (PACKAGED_ABIS.contains(abi)) {
                packagedAbi = true;
                break;
            }
        }
        if (!packagedAbi) return false;
        try {
            // Initializing Ass loads libasskt.so, proving the ABI can load
            // before Silo advertises authored ASS styling to the server.
            Class.forName(Ass.class.getName(), true, LibassBridge.class.getClassLoader());
            return true;
        } catch (LinkageError | ReflectiveOperationException error) {
            return false;
        }
    }

    private static boolean probeMatroskaIntegration() {
        try {
            // AssMatroskaExtractor verifies the private Media3 field names it
            // needs in its static initializer. Probe that compatibility before
            // claiming embedded-font support to the V3 server.
            Class.forName(
                    AssMatroskaExtractor.class.getName(),
                    true,
                    LibassBridge.class.getClassLoader()
            );
            return true;
        } catch (LinkageError | ReflectiveOperationException error) {
            return false;
        }
    }

    private static final class SiloLibassRenderer extends NoSampleRenderer {
        private final AssHandler handler;
        private final LongSupplier subtitleOffsetUs;

        private SiloLibassRenderer(AssHandler handler, LongSupplier subtitleOffsetUs) {
            this.handler = handler;
            this.subtitleOffsetUs = subtitleOffsetUs;
        }

        @Override
        public String getName() {
            return "SiloLibassRenderer";
        }

        @Override
        public void render(long positionUs, long elapsedRealtimeUs) {
            // Positive Silo offset means "show later", so render libass at an
            // earlier media time. Ordinary Media3 cues receive the same offset
            // by shifting their start timestamps in OffsetSubtitleParserFactory.
            try {
                handler.setVideoTime(
                        adjustedPositionUs(positionUs, subtitleOffsetUs.getAsLong())
                );
            } catch (RuntimeException error) {
                // AssHandler initializes its internal render handler
                // asynchronously when the subtitle surface attaches; Media3's
                // render loop can land here a few frames earlier, and the
                // resulting lateinit UninitializedPropertyAccessException used
                // to surface as a fatal ExoPlaybackException that killed
                // playback outright. Dropping the frame during that startup
                // race is harmless — subtitles begin one frame later. Checked
                // by name because this Java module has no compile-time Kotlin
                // stdlib dependency; anything else still propagates.
                if (!"UninitializedPropertyAccessException"
                        .equals(error.getClass().getSimpleName())) {
                    throw error;
                }
            }
        }
    }

    static long adjustedPositionUs(long rendererPositionUs, long subtitleOffsetUs) {
        return rendererPositionUs
                - MEDIA3_RENDERER_POSITION_OFFSET_US
                - subtitleOffsetUs;
    }
}
