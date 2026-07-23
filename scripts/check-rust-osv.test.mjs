import assert from "node:assert/strict";
import { once } from "node:events";
import http from "node:http";
import { spawn } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("./check-rust-osv.mjs", import.meta.url));
const lockfile = `version = 4

[[package]]
name = "safe-crate"
version = "1.2.3"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

[[package]]
name = "affected-crate"
version = "4.5.6"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"

[[package]]
name = "workspace-only"
version = "0.1.0"

[[package]]
name = "other-registry"
version = "7.8.9"
source = "registry+https://registry.example.com/index"
checksum = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
`;

async function runCheck(response, input = lockfile) {
  let requestBody;
  const server = http.createServer((request, reply) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => {
      requestBody = JSON.parse(body);
      reply.writeHead(200, { "content-type": "application/json" });
      reply.end(JSON.stringify(response));
    });
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address();

  const child = spawn(process.execPath, [script], {
    env: { ...process.env, OSV_API_URL: `http://127.0.0.1:${port}/v1/querybatch` },
    stdio: ["pipe", "pipe", "pipe"],
  });
  child.stdin.end(input);
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8").on("data", (chunk) => {
    stdout += chunk;
  });
  child.stderr.setEncoding("utf8").on("data", (chunk) => {
    stderr += chunk;
  });
  const [exitCode] = await once(child, "exit");
  server.close();
  await once(server, "close");
  return { exitCode, stdout, stderr, requestBody };
}

test("submits only crates.io packages and succeeds when none are affected", async () => {
  const result = await runCheck({ results: [{}, {}] });

  assert.equal(result.exitCode, 0);
  assert.deepEqual(result.requestBody, {
    queries: [
      { package: { ecosystem: "crates.io", name: "safe-crate" }, version: "1.2.3" },
      { package: { ecosystem: "crates.io", name: "affected-crate" }, version: "4.5.6" },
    ],
  });
  assert.equal(result.stdout, "No affected crates.io packages found.\n");
  assert.equal(result.stderr, "");
});

test("fails affected results and prints package names without advisory details", async () => {
  const result = await runCheck({
    results: [{}, { vulns: [{ id: "GHSA-secret", summary: "do not print me" }] }],
  });

  assert.equal(result.exitCode, 1);
  assert.equal(result.stdout, "");
  assert.equal(result.stderr, "Affected crates.io packages:\naffected-crate\n");
});

test("fails closed for malformed OSV results and vulnerability entries", async () => {
  const malformedResponses = [
    null,
    "not-an-object",
    { results: "not-an-array" },
    { results: {} },
    { results: ["not-an-object", {}] },
    { results: [42, {}] },
    { results: [{}, { vulns: "not-an-array" }] },
    { results: [{}, { vulns: [null] }] },
    { results: [{}, { vulns: [{}] }] },
    { results: [{}, { vulns: [{ id: "" }] }] },
  ];

  for (const response of malformedResponses) {
    const result = await runCheck(response);
    assert.equal(result.exitCode, 1, JSON.stringify(response));
    assert.equal(result.stdout, "");
    assert.match(result.stderr, /Malformed OSV querybatch response/);
  }
});

test("fails a mixed lockfile when one crates.io package block is malformed", async () => {
  const malformedLockfile = `version = 4

[[package]]
name = "safe-crate"
version = "1.2.3"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

[[package]]
name = ["silently-skipped"]
version = "4.5.6"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
`;

  const result = await runCheck({ results: [{}] }, malformedLockfile);

  assert.equal(result.exitCode, 1);
  assert.equal(result.stdout, "");
  assert.match(result.stderr, /Invalid Cargo\.lock package block 2: name/);
  assert.equal(result.requestBody, undefined);
});

test("rejects crates.io-like registry source variants instead of omitting them", async () => {
  const sourceVariants = [
    "registry+https://github.com/rust-lang/crates.io-index/",
    "registry+https://GitHub.com/rust-lang/crates.io-index",
    "registry+https://github.com/rust-lang/%63rates.io-index",
    "registry+https://github.com:443/rust-lang/crates.io-index",
    "registry+https://github.com/rust-lang/crates.io-index?mirror=1",
    "registry+https://github.com/rust-lang/crates.io-index#fragment",
    "registry+https://user@github.com/rust-lang/crates.io-index",
    "registry+https://index.crates.io/",
    ...[4, 8, 9].map(
      (depth) => `registry+https://github.com/rust-lang/${encodePercentDepth("crates.io-index", depth)}`,
    ),
    "registry+https://registry.example.com/%ZZ",
  ];

  for (const source of sourceVariants) {
    const variantLockfile = `version = 4

[[package]]
name = "safe-crate"
version = "1.2.3"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

[[package]]
name = "lookalike-crate"
version = "4.5.6"
source = "${source}"
checksum = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
`;
    const result = await runCheck({ results: [{}] }, variantLockfile);

    assert.equal(result.exitCode, 1, source);
    assert.equal(result.stdout, "");
    assert.match(result.stderr, /Invalid Cargo\.lock package block 2: source/);
    assert.equal(result.requestBody, undefined);
  }
});

function encodePercentDepth(value, depth) {
  let encoded = `%${value.codePointAt(0).toString(16)}${value.slice(1)}`;
  for (let pass = 1; pass < depth; pass += 1) {
    encoded = encodeURIComponent(encoded);
  }
  return encoded;
}
