export function verifyCompanionPackageFreshness({ sourceManifest, archiveManifest }) {
  if (!isObject(sourceManifest) || !isObject(archiveManifest)) {
    throw codedError('companion-package-manifest-invalid');
  }

  const sourceWs = sourceManifest.dependencies?.ws;
  if (typeof sourceWs !== 'string' || sourceWs.length === 0 || sourceManifest.devDependencies?.ws !== undefined) {
    throw codedError('companion-source-ws-runtime-dependency-invalid');
  }
  if (archiveManifest.dependencies?.ws !== sourceWs || archiveManifest.devDependencies?.ws !== undefined) {
    throw codedError('companion-package-archive-stale');
  }
  if (canonicalJson(sourceManifest) !== canonicalJson(archiveManifest)) {
    throw codedError('companion-package-archive-stale');
  }

  return {
    name: sourceManifest.name,
    version: sourceManifest.version,
    wsVersion: sourceWs,
  };
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (isObject(value)) {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function codedError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}
