/**
 * Minimal IDL-driven Borsh codec for the Vowed program (Anchor IDL v0.30+ format).
 * Values: u8/u16/u32/i16 -> number, u64/i64 -> bigint, bool -> boolean, pubkey -> base58 string,
 * [u8; N] -> Uint8Array, unit enums -> variant name (string), structs -> plain objects keyed by IDL field name.
 */
import { PublicKey } from "@solana/web3.js";

export type IdlType =
  | string
  | { array: [IdlType, number] }
  | { vec: IdlType }
  | { option: IdlType }
  | { defined: { name: string } };

interface IdlField {
  name: string;
  type: IdlType;
}
interface IdlTypeDef {
  name: string;
  type: { kind: "struct"; fields: IdlField[] } | { kind: "enum"; variants: { name: string }[] };
}
export interface Idl {
  address: string;
  instructions: { name: string; discriminator: number[]; args: IdlField[] }[];
  accounts: { name: string; discriminator: number[] }[];
  events: { name: string; discriminator: number[] }[];
  types: IdlTypeDef[];
  errors: { code: number; name: string; msg: string }[];
}

export class Writer {
  private chunks: number[] = [];
  bytes(b: ArrayLike<number>) {
    for (let i = 0; i < b.length; i++) this.chunks.push(b[i]!);
  }
  uint(value: bigint | number, size: number) {
    let v = BigInt(value);
    if (v < 0n || v >= 1n << BigInt(size * 8)) throw new Error(`u${size * 8} out of range: ${value}`);
    for (let i = 0; i < size; i++) {
      this.chunks.push(Number(v & 0xffn));
      v >>= 8n;
    }
  }
  int(value: bigint | number, size: number) {
    const v = BigInt(value);
    const bits = BigInt(size * 8);
    if (v < -(1n << (bits - 1n)) || v >= 1n << (bits - 1n)) throw new Error(`i${size * 8} out of range: ${value}`);
    this.uint(v < 0n ? v + (1n << bits) : v, size);
  }
  toBytes() {
    return Uint8Array.from(this.chunks);
  }
}

export class Reader {
  private pos = 0;
  constructor(private buf: Uint8Array) {}
  get offset() {
    return this.pos;
  }
  take(n: number): Uint8Array {
    if (this.pos + n > this.buf.length) throw new Error("borsh: unexpected end of data");
    const out = this.buf.subarray(this.pos, this.pos + n);
    this.pos += n;
    return out;
  }
  uint(size: number): bigint {
    const b = this.take(size);
    let v = 0n;
    for (let i = size - 1; i >= 0; i--) v = (v << 8n) | BigInt(b[i]!);
    return v;
  }
  int(size: number): bigint {
    const v = this.uint(size);
    const bits = BigInt(size * 8);
    return v >= 1n << (bits - 1n) ? v - (1n << bits) : v;
  }
}

const UINT_SIZES: Record<string, number> = { u8: 1, u16: 2, u32: 4, u64: 8 };
const INT_SIZES: Record<string, number> = { i8: 1, i16: 2, i32: 4, i64: 8 };

export class Codec {
  private types = new Map<string, IdlTypeDef>();
  constructor(readonly idl: Idl) {
    for (const t of idl.types) this.types.set(t.name, t);
  }

  encodeType(w: Writer, ty: IdlType, value: unknown): void {
    if (typeof ty === "string") {
      if (ty in UINT_SIZES) {
        const n = UINT_SIZES[ty]!;
        w.uint(value as bigint | number, n);
      } else if (ty in INT_SIZES) {
        w.int(value as bigint | number, INT_SIZES[ty]!);
      } else if (ty === "bool") {
        w.uint(value ? 1 : 0, 1);
      } else if (ty === "pubkey") {
        w.bytes(new PublicKey(value as string | PublicKey).toBytes());
      } else {
        throw new Error(`unsupported primitive type ${ty}`);
      }
      return;
    }
    if ("array" in ty) {
      const [inner, n] = ty.array;
      const arr = value as ArrayLike<unknown>;
      if (arr.length !== n) throw new Error(`expected array of ${n}, got ${arr.length}`);
      if (inner === "u8") w.bytes(arr as ArrayLike<number>);
      else for (let i = 0; i < n; i++) this.encodeType(w, inner, arr[i]);
      return;
    }
    if ("vec" in ty) {
      const arr = value as unknown[];
      w.uint(arr.length, 4);
      for (const item of arr) this.encodeType(w, ty.vec, item);
      return;
    }
    if ("option" in ty) {
      if (value === null || value === undefined) w.uint(0, 1);
      else {
        w.uint(1, 1);
        this.encodeType(w, ty.option, value);
      }
      return;
    }
    const def = this.types.get(ty.defined.name);
    if (!def) throw new Error(`unknown type ${ty.defined.name}`);
    if (def.type.kind === "struct") {
      const obj = value as Record<string, unknown>;
      for (const f of def.type.fields) {
        if (!(f.name in obj)) throw new Error(`missing field ${def.name}.${f.name}`);
        this.encodeType(w, f.type, obj[f.name]);
      }
    } else {
      const idx = def.type.variants.findIndex((v) => v.name === value);
      if (idx < 0) throw new Error(`unknown variant ${String(value)} for ${def.name}`);
      w.uint(idx, 1);
    }
  }

  decodeType(r: Reader, ty: IdlType): unknown {
    if (typeof ty === "string") {
      if (ty in UINT_SIZES) {
        const n = UINT_SIZES[ty]!;
        const v = r.uint(n);
        return n <= 4 ? Number(v) : v;
      }
      if (ty in INT_SIZES) {
        const n = INT_SIZES[ty]!;
        const v = r.int(n);
        return n <= 4 ? Number(v) : v;
      }
      if (ty === "bool") return r.uint(1) !== 0n;
      if (ty === "pubkey") return new PublicKey(r.take(32)).toBase58();
      throw new Error(`unsupported primitive type ${ty}`);
    }
    if ("array" in ty) {
      const [inner, n] = ty.array;
      if (inner === "u8") return Uint8Array.from(r.take(n));
      return Array.from({ length: n }, () => this.decodeType(r, inner));
    }
    if ("vec" in ty) {
      const n = Number(r.uint(4));
      if (n > 10_000) throw new Error("borsh: vec too long");
      return Array.from({ length: n }, () => this.decodeType(r, ty.vec));
    }
    if ("option" in ty) {
      return r.uint(1) === 0n ? null : this.decodeType(r, ty.option);
    }
    const def = this.types.get(ty.defined.name);
    if (!def) throw new Error(`unknown type ${ty.defined.name}`);
    if (def.type.kind === "struct") {
      const out: Record<string, unknown> = {};
      for (const f of def.type.fields) out[f.name] = this.decodeType(r, f.type);
      return out;
    }
    const idx = Number(r.uint(1));
    const v = def.type.variants[idx];
    if (!v) throw new Error(`invalid enum variant ${idx} for ${def.name}`);
    return v.name;
  }

  /** discriminator + args */
  encodeInstruction(name: string, args: Record<string, unknown>): Uint8Array {
    const ix = this.idl.instructions.find((i) => i.name === name);
    if (!ix) throw new Error(`unknown instruction ${name}`);
    const w = new Writer();
    w.bytes(ix.discriminator);
    for (const a of ix.args) {
      if (!(a.name in args)) throw new Error(`missing arg ${name}.${a.name}`);
      this.encodeType(w, a.type, args[a.name]);
    }
    return w.toBytes();
  }

  /** Decodes account data (8-byte discriminator first). Throws if the discriminator does not match. */
  decodeAccount<T = Record<string, unknown>>(name: string, data: Uint8Array): T {
    const acc = this.idl.accounts.find((a) => a.name === name);
    if (!acc) throw new Error(`unknown account ${name}`);
    for (let i = 0; i < 8; i++) {
      if (data[i] !== acc.discriminator[i]) throw new Error(`account discriminator mismatch for ${name}`);
    }
    return this.decodeType(new Reader(data.subarray(8)), { defined: { name } }) as T;
  }

  /** Decodes one `Program data: <base64>` event payload, or null when it is not one of our events. */
  decodeEvent(data: Uint8Array): { name: string; data: Record<string, unknown> } | null {
    if (data.length < 8) return null;
    for (const ev of this.idl.events) {
      let match = true;
      for (let i = 0; i < 8; i++) if (data[i] !== ev.discriminator[i]) match = false;
      if (match) {
        const decoded = this.decodeType(new Reader(data.subarray(8)), { defined: { name: ev.name } });
        return { name: ev.name, data: decoded as Record<string, unknown> };
      }
    }
    return null;
  }
}
