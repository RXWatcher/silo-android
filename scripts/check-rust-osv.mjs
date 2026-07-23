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
  const results = validateOsvResponse(payload, batch.length);
  results.forEach((vulnerabilities, index) => {
    if (vulnerabilities.length > 0) {
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
  const lines = lockfile.split(/\r?\n/);
  const packageHeaders = [];
  let lockVersion;
  let sawPackage = false;

  lines.forEach((line, index) => {
    if (/^\s*\[\[package/.test(line) && !/^\s*\[\[package\]\]\s*(?:#.*)?$/.test(line)) {
      throw new Error(`Invalid Cargo.lock package header at line ${index + 1}`);
    }
    if (/^\s*\[\[package\]\]\s*(?:#.*)?$/.test(line)) {
      sawPackage = true;
      packageHeaders.push(index);
      return;
    }
    if (!sawPackage) {
      if (/^\s*(?:#.*)?$/.test(line)) return;
      const versionMatch = line.match(/^\s*version\s*=\s*(\d+)\s*(?:#.*)?$/);
      if (!versionMatch || lockVersion !== undefined) {
        throw new Error(`Invalid Cargo.lock preamble at line ${index + 1}`);
      }
      lockVersion = Number(versionMatch[1]);
    }
  });
  if (lockVersion !== 4) {
    throw new Error("Invalid Cargo.lock: expected lockfile version 4");
  }
  if (packageHeaders.length === 0) {
    throw new Error("Invalid Cargo.lock: no package blocks");
  }

  const packages = [];
  const identities = new Set();
  packageHeaders.forEach((headerIndex, packageIndex) => {
    const end = packageHeaders[packageIndex + 1] ?? lines.length;
    const fields = parsePackageBlock(lines.slice(headerIndex + 1, end), packageIndex + 1);
    const identity = `${fields.source ?? "local"}\0${fields.name}\0${fields.version}`;
    if (identities.has(identity)) {
      throw new Error(`Invalid Cargo.lock package block ${packageIndex + 1}: duplicate package identity`);
    }
    identities.add(identity);

    if (isCratesIoSource(fields.source)) {
      packages.push({ name: fields.name, version: fields.version });
    }
  });
  return packages;
}

function parsePackageBlock(lines, packageNumber) {
  const fields = {};
  let inDependencies = false;

  for (const line of lines) {
    if (inDependencies) {
      if (/^\s*\]\s*(?:#.*)?$/.test(line)) {
        inDependencies = false;
        continue;
      }
      const dependency = line.match(/^\s*("(?:[^"\\]|\\.)*")\s*,?\s*(?:#.*)?$/);
      if (!dependency) {
        throw packageError(packageNumber, "dependencies");
      }
      parseTomlString(dependency[1], packageNumber, "dependencies");
      continue;
    }

    if (/^\s*(?:#.*)?$/.test(line)) continue;
    const assignment = line.match(/^\s*([A-Za-z][A-Za-z0-9_-]*)\s*=\s*(.*?)\s*$/);
    if (!assignment) {
      throw packageError(packageNumber, "syntax");
    }
    const [, key, value] = assignment;
    if (!["name", "version", "source", "checksum", "dependencies"].includes(key)) {
      throw packageError(packageNumber, `unexpected field ${key}`);
    }
    if (Object.hasOwn(fields, key)) {
      throw packageError(packageNumber, `duplicate ${key}`);
    }
    if (key === "dependencies") {
      fields.dependencies = true;
      if (value === "[]") continue;
      if (value !== "[") throw packageError(packageNumber, "dependencies");
      inDependencies = true;
      continue;
    }
    fields[key] = parseTomlString(value, packageNumber, key);
  }
  if (inDependencies) throw packageError(packageNumber, "unterminated dependencies");

  for (const key of ["name", "version"]) {
    if (!Object.hasOwn(fields, key)) throw packageError(packageNumber, `missing ${key}`);
  }
  if (!/^[A-Za-z0-9_-]+$/.test(fields.name)) {
    throw packageError(packageNumber, "name");
  }
  if (!isSemver(fields.version)) {
    throw packageError(packageNumber, "version");
  }
  if (fields.source !== undefined &&
      !/^(?:registry|git)\+https?:\/\/\S+$/.test(fields.source)) {
    throw packageError(packageNumber, "source");
  }
  if (isCratesIoLikeSource(fields.source)) {
    throw packageError(packageNumber, "source");
  }
  if (isCratesIoSource(fields.source)) {
    if (!/^[a-f0-9]{64}$/.test(fields.checksum ?? "")) {
      throw packageError(packageNumber, "checksum");
    }
  } else if (fields.checksum !== undefined && fields.source === undefined) {
    throw packageError(packageNumber, "checksum without source");
  }
  return fields;
}

function parseTomlString(value, packageNumber, key) {
  try {
    const parsed = JSON.parse(value);
    if (typeof parsed !== "string") throw new Error();
    return parsed;
  } catch {
    throw packageError(packageNumber, key);
  }
}

function isSemver(version) {
  return /^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.test(version);
}

function isCratesIoSource(source) {
  return source === "registry+https://github.com/rust-lang/crates.io-index";
}

function isCratesIoLikeSource(source) {
  if (source === undefined || isCratesIoSource(source) || !source.startsWith("registry+")) {
    return false;
  }
  const rawUrl = source.slice("registry+".length);
  try {
    const url = new URL(rawUrl);
    const hostname = url.hostname.toLowerCase().replace(/\.$/, "");
    const pathname = decodeUrlComponent(url.pathname).toLowerCase().replace(/\/+$/, "");
    if (hostname === "github.com" && pathname === "/rust-lang/crates.io-index") {
      return true;
    }
    if (hostname === "index.crates.io") return true;
  } catch {
    // The source syntax check reports malformed non-URLs. Keep the raw
    // crates.io check below fail-closed for unusual URL parser edge cases.
  }
  return /(?:^|[./@])crates(?:\.|%2e)io(?:[/:?#]|$)/i.test(rawUrl) ||
    /github(?:\.|%2e)com\/rust-lang\/crates(?:\.|%2e)io-index/i.test(rawUrl);
}

function decodeUrlComponent(value) {
  let decoded = value;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const next = decodeURIComponent(decoded);
    if (next === decoded) return decoded;
    decoded = next;
  }
  return decoded;
}

function packageError(packageNumber, field) {
  return new Error(`Invalid Cargo.lock package block ${packageNumber}: ${field}`);
}

function validateOsvResponse(payload, expectedLength) {
  if (!isObject(payload) ||
      !Array.isArray(payload.results) ||
      payload.results.length !== expectedLength) {
    throw new Error("Malformed OSV querybatch response: invalid results array");
  }
  return payload.results.map((result, resultIndex) => {
    if (!isObject(result)) {
      throw new Error(`Malformed OSV querybatch response: result ${resultIndex + 1}`);
    }
    if (!Object.hasOwn(result, "vulns")) return [];
    if (!Array.isArray(result.vulns)) {
      throw new Error(`Malformed OSV querybatch response: result ${resultIndex + 1} vulns`);
    }
    result.vulns.forEach((vulnerability, vulnerabilityIndex) => {
      if (!isObject(vulnerability) ||
          typeof vulnerability.id !== "string" ||
          !/^\S+$/.test(vulnerability.id)) {
        throw new Error(
          `Malformed OSV querybatch response: result ${resultIndex + 1} vulnerability ${vulnerabilityIndex + 1}`,
        );
      }
    });
    return result.vulns;
  });
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}
