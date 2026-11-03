/** One DNS-free, no-normalization syntax for every configured WebSocket endpoint. */
const MAX_ENDPOINT_LENGTH = 2_048;

export function isValidEndpointSyntax(value: unknown): value is string {
  if (typeof value !== 'string' || value.length < 1 || value.length > MAX_ENDPOINT_LENGTH) return false;
  for (let index = 0; index < value.length; index++) {
    const character = value.charCodeAt(index);
    if (character < 0x21 || character > 0x7e || character === 0x5c || character === 0x3f || character === 0x23) return false;
  }

  let scheme: 'ws' | 'wss';
  let authorityStart: number;
  if (value.startsWith('ws://')) {
    scheme = 'ws';
    authorityStart = 5;
  } else if (value.startsWith('wss://')) {
    scheme = 'wss';
    authorityStart = 6;
  } else {
    return false;
  }

  const pathStart = value.indexOf('/', authorityStart);
  const authority = pathStart < 0 ? value.slice(authorityStart) : value.slice(authorityStart, pathStart);
  const path = pathStart < 0 ? null : value.slice(pathStart);
  if (authority.length === 0 || authority.includes('@') || !validPath(path)) return false;

  let host: string;
  let port: string | null = null;
  if (authority.startsWith('[')) {
    const closingBracket = authority.indexOf(']');
    if (closingBracket <= 1 || !validIpv6(authority.slice(1, closingBracket))) return false;
    host = authority.slice(0, closingBracket + 1);
    const remainder = authority.slice(closingBracket + 1);
    if (remainder.length > 0) {
      if (!remainder.startsWith(':') || remainder.length === 1) return false;
      port = remainder.slice(1);
    }
  } else {
    if (authority.includes('[') || authority.includes(']')) return false;
    const colon = authority.indexOf(':');
    if (colon >= 0) {
      if (colon === 0 || colon !== authority.lastIndexOf(':') || colon === authority.length - 1) return false;
      host = authority.slice(0, colon);
      port = authority.slice(colon + 1);
    } else {
      host = authority;
    }
    if (!validHost(host)) return false;
  }

  if (port !== null && !validPort(port)) return false;
  return scheme !== 'ws' || host === '127.0.0.1' || host === '[::1]';
}

function validPath(path: string | null): boolean {
  if (path === null || path === '/') return true;
  if (!path.startsWith('/') || path.includes('//')) return false;
  const segments = path.slice(1).split('/');
  return segments.every((segment, index) => {
    if (segment.length === 0) return index === segments.length - 1;
    if (segment === '.' || segment === '..') return false;
    return /^[A-Za-z0-9._~-]+$/u.test(segment);
  });
}

function validPort(port: string): boolean {
  if (!/^[1-9][0-9]{0,4}$/u.test(port)) return false;
  const number = Number(port);
  return number >= 1 && number <= 65_535;
}

function validHost(host: string): boolean {
  if (host.length === 0) return false;
  if (/^[0-9.]+$/u.test(host)) return validIpv4(host);
  if (host.length > 253) return false;

  const labels = host.split('.');
  let containsLetter = false;
  for (const label of labels) {
    if (label.length < 1 || label.length > 63 || !asciiAlphaNumeric(label[0]!) ||
        !asciiAlphaNumeric(label[label.length - 1]!)) return false;
    for (const character of label) {
      if (!asciiAlphaNumeric(character) && character !== '-') return false;
      if (character >= 'a' && character <= 'z') containsLetter = true;
    }
  }
  const finalLabel = labels[labels.length - 1]!;
  return containsLetter && !isWhatwgIpv4Number(finalLabel);
}

function validIpv4(host: string): boolean {
  const octets = host.split('.');
  if (octets.length !== 4) return false;
  return octets.every((octet) => {
    if (octet.length < 1 || octet.length > 3 || octet.length > 1 && octet.startsWith('0')) return false;
    return /^[0-9]+$/u.test(octet) && Number(octet) <= 255;
  });
}

function isWhatwgIpv4Number(label: string): boolean {
  if (/^[0-9]+$/u.test(label)) return true;
  return /^0x[0-9a-f]*$/u.test(label);
}

function validIpv6(value: string): boolean {
  if (value.length === 0 || !/^[0-9a-f:]+$/u.test(value)) return false;
  const compression = value.indexOf('::');
  if (compression >= 0 && compression !== value.lastIndexOf('::')) return false;

  let groups: number[];
  if (compression < 0) {
    const parsed = parseIpv6Side(value);
    if (parsed === null || parsed.length !== 8) return false;
    groups = parsed;
  } else {
    const left = parseIpv6Side(value.slice(0, compression));
    const right = parseIpv6Side(value.slice(compression + 2));
    if (left === null || right === null || left.length + right.length >= 8) return false;
    groups = [...left, ...new Array<number>(8 - left.length - right.length).fill(0), ...right];
  }
  return value === canonicalIpv6(groups);
}

function parseIpv6Side(side: string): number[] | null {
  if (side.length === 0) return [];
  const groups: number[] = [];
  for (const group of side.split(':')) {
    if (!/^[0-9a-f]{1,4}$/u.test(group)) return null;
    groups.push(Number.parseInt(group, 16));
  }
  return groups;
}

function canonicalIpv6(groups: readonly number[]): string {
  let bestStart = -1;
  let bestLength = 1;
  for (let index = 0; index < groups.length;) {
    if (groups[index] !== 0) {
      index++;
      continue;
    }
    let end = index;
    while (end < groups.length && groups[end] === 0) end++;
    if (end - index > bestLength) {
      bestStart = index;
      bestLength = end - index;
    }
    index = end;
  }
  if (bestStart < 0) return groups.map((group) => group.toString(16)).join(':');
  const left = groups.slice(0, bestStart).map((group) => group.toString(16)).join(':');
  const right = groups.slice(bestStart + bestLength).map((group) => group.toString(16)).join(':');
  return `${left}::${right}`;
}

function asciiAlphaNumeric(character: string): boolean {
  return /^[a-z0-9]$/u.test(character);
}
