/** Small synchronous SHA-256 for stable legacy replay/source identities. Not credential storage. */
export function utf8(text: string): number[] {
  const bytes: number[] = [];
  for (let i = 0; i < text.length; i++) {
    let cp = text.codePointAt(i)!;
    if (cp > 0xffff) i++;
    if (cp >= 0xd800 && cp <= 0xdfff) cp = 0xfffd;
    if (cp < 128) bytes.push(cp);
    else if (cp < 2048) bytes.push(192 | (cp >> 6), 128 | (cp & 63));
    else if (cp < 65536)
      bytes.push(224 | (cp >> 12), 128 | ((cp >> 6) & 63), 128 | (cp & 63));
    else
      bytes.push(
        240 | (cp >> 18),
        128 | ((cp >> 12) & 63),
        128 | ((cp >> 6) & 63),
        128 | (cp & 63),
      );
  }
  return bytes;
}
export function sha256(input: string | number[]): string {
  const k = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
    0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
    0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
    0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ];
  const h = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c,
    0x1f83d9ab, 0x5be0cd19,
  ];
  const data = typeof input === 'string' ? utf8(input) : [...input];
  const length = data.length * 8;
  data.push(128);
  while (data.length % 64 !== 56) data.push(0);
  const high = Math.floor(length / 0x100000000),
    low = length >>> 0;
  for (const value of [high, low])
    data.push(
      (value >>> 24) & 255,
      (value >>> 16) & 255,
      (value >>> 8) & 255,
      value & 255,
    );
  const rr = (x: number, n: number) => (x >>> n) | (x << (32 - n));
  for (let off = 0; off < data.length; off += 64) {
    const w = Array<number>(64).fill(0);
    for (let j = 0; j < 16; j++)
      w[j] =
        (data[off + j * 4] << 24) |
        (data[off + j * 4 + 1] << 16) |
        (data[off + j * 4 + 2] << 8) |
        data[off + j * 4 + 3];
    for (let j = 16; j < 64; j++) {
      const a = w[j - 15],
        b = w[j - 2];
      w[j] =
        (w[j - 16] +
          (rr(a, 7) ^ rr(a, 18) ^ (a >>> 3)) +
          w[j - 7] +
          (rr(b, 17) ^ rr(b, 19) ^ (b >>> 10))) |
        0;
    }
    let [a, b, c, d, e, f, g, z] = h;
    for (let j = 0; j < 64; j++) {
      const t1 =
          (z +
            (rr(e, 6) ^ rr(e, 11) ^ rr(e, 25)) +
            ((e & f) ^ (~e & g)) +
            k[j] +
            w[j]) |
          0,
        t2 =
          ((rr(a, 2) ^ rr(a, 13) ^ rr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) |
          0;
      z = g;
      g = f;
      f = e;
      e = (d + t1) | 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) | 0;
    }
    [a, b, c, d, e, f, g, z].forEach((v, j) => (h[j] = (h[j] + v) | 0));
  }
  return h.map(v => (v >>> 0).toString(16).padStart(8, '0')).join('');
}
export function canonical(value: unknown): string {
  if (value === undefined || value === null) return 'null';
  if (Array.isArray(value))
    return '[' + value.map(v => canonical(v) + ',').join('') + ']';
  if (typeof value === 'object')
    return (
      '{' +
      Object.keys(value)
        .sort()
        .map(
          key =>
            JSON.stringify(key) +
            ':' +
            canonical((value as Record<string, unknown>)[key]) +
            ',',
        )
        .join('') +
      '}'
    );
  return JSON.stringify(value);
}
