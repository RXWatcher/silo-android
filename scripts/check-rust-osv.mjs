#!/usr/bin/env node

const OSV_API_URL = process.env.OSV_API_URL ?? "https://api.osv.dev/v1/querybatch";
const input = await readStdin();
const packages = parseCratesIoPackages(input);

if (packages.length === 0) {
  throw new Error("Cargo.lock contains no crates.io packages");
}

const affected = [];
for (let offset = 0; offset < packages.length; offset += 1000) {
  const batch = packages.slice(offset, offset + 1000);
  const response = await fetch(OSV_API_URL, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      queries: batch.map(({ name, version }) => ({
        package: { ecosystem: "crates.io", name },
        version,
      })),
    }),
  });
  if (!response.ok) {
    throw new Error(`OSV querybatch failed with HTTP ${response.status}`);
  }

  const payload = await response.json();
  if (!Array.isArray(payload.results) || payload.results.length !== batch.length) {
    throw new Error("OSV querybatch returned an unexpected result count");
  }
  payload.results.forEach((result, index) => {
    if (Array.isArray(result.vulns) && result.vulns.length > 0) {
      affected.push(batch[index].name);
    }
  });
}

if (affected.length > 0) {
  process.stderr.write(`Affected crates.io packages:\n${[...new Set(affected)].sort().join("\n")}\n`);
  process.exitCode = 1;
} else {
  process.stdout.write("No affected crates.io packages found.\n");
}

function parseCratesIoPackages(lockfile) {
  const packages = [];
  for (const block of lockfile.split(/^\[\[package\]\]\s*$/m).slice(1)) {
    const name = readTomlString(block, "name");
    const version = readTomlString(block, "version");
    const source = readTomlString(block, "source");
    if (name && version && source?.startsWith("registry+https://github.com/rust-lang/crates.io-index")) {
      packages.push({ name, version });
    }
  }
  return packages;
}

function readTomlString(block, key) {
  const match = block.match(new RegExp(`^${key} = "([^"\\\\]*)"\\s*$`, "m"));
  return match?.[1];
}

async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}
