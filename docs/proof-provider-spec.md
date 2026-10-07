# Vowed proof provider spec (version 1)

Status: **working draft, implemented for one sample provider only.** Nothing here means another app has integrated it. Do not claim third-party integrations.

A *proof provider* is any app or service that can say, with a signature, "this person did this much of this thing in this time window". The person hands the signed statement (an *attestation*) to Vowed, and Vowed's backend verifies it. In this version an accepted attestation is **recorded with a LOW trust tier** and does **not** count as a daily check-in or support a stake; making providers count toward check-ins needs an operator allow-list and a program-side proof type, which are future work.

## The statement

JSON object, all fields required:

| field | type | meaning |
|---|---|---|
| `v` | `1` | format version |
| `provider` | string, `[a-z0-9][a-z0-9._-]{2,63}` | provider id, for example `sample.focus` |
| `keyId` | string, `[A-Za-z0-9._-]{1,64}` | which of the provider's keys signed this |
| `wallet` | base58 string | the person's Solana wallet; must be the wallet that submits it |
| `metric` | string, `[a-z][a-z0-9_]{1,47}` | what was measured, for example `focus_seconds`, `reading_minutes` |
| `value` | number, 0 to 1e9 (use a whole number in version 1) | the measured amount |
| `unit` | string, 1 to 16 chars | `seconds`, `minutes`, `steps`, ... |
| `windowStart`, `windowEnd` | unix seconds | the period measured; `windowEnd >= windowStart`, at most 24 h long, and not later than `issuedAt + 60` |
| `nonce` | string, 16 to 64 chars | unique per statement (random bytes, base64) |
| `issuedAt` | unix seconds | when the provider signed it |
| `alg` | `"ES256"` or `"EdDSA"` | signature algorithm |
| `signature` | base64 | see below |

## What is signed

The UTF-8 bytes of `VOWED-ATTESTATION-v1` + a line feed + the JSON text of **every field except `signature`**, keys in alphabetical order, no spaces or line breaks, strings escaped as in `JSON.stringify` (backslash and double quote escaped, control characters as `\u00xx`). Numbers are printed as plain integers.

* `ES256`: ECDSA over the P-256 curve with SHA-256, signature in ASN.1 DER form, base64. The public key is registered as base64 SubjectPublicKeyInfo (DER).
* `EdDSA`: Ed25519, 64-byte signature, base64. The public key is registered as the raw 32 bytes, base64.

A reference vector that the backend and the Android sample provider both check is `shared/test-vectors/attestation.json`.

## How a key becomes known to Vowed

`provider_keys` on the backend maps `(provider, keyId)` to a public key. Keys are added in two ways:

1. By the operator (a row for a provider the operator vets; not used yet).
2. For the **sample provider only**, on a devnet server with `SAMPLE_PROVIDER_ENABLED=true`: the app calls `POST /v1/providers/sample/register` with the public key it generated in the Android Keystore. That key is bound to the registering wallet and cannot be replaced by another wallet.

## Submitting

`POST /v1/attestations` with the JSON above and the person's normal `Authorization: Bearer` token. The backend refuses, with a code you can act on:

| code | why |
|---|---|
| `attestation_wrong_wallet` | `wallet` is not the signed-in wallet |
| `attestation_bad_window` | window reversed, longer than 24 h, or ends after `issuedAt + 60` |
| `attestation_stale` | `issuedAt` is more than 10 minutes old |
| `attestation_from_the_future` | `issuedAt` is more than 60 seconds ahead |
| `unknown_provider_key` | the key is not registered for this provider (and this wallet), or `alg` does not match the registered key |
| `bad_signature` | the signature does not match the bytes above |
| `attestation_replayed` (409) | this `nonce` was already accepted |

On success the answer is `{ accepted: true, provider, metric, value, trustTier: "low", note }`.

## The Kotlin interface

`app.vowed.proof.provider.ProofProvider` (id, keyId, `attest(...)`). `SampleFocusProvider` implements it with a P-256 key created in the Android Keystore on first use (no signing key is stored in the repository or the APK). Its output is tested against the shared vector and against a generated key.

## Privacy and trust notes

* A statement contains only a metric, a number, a time window and the wallet address. Anything finer (what was read, where) stays with the provider and the person.
* Because the person submits the statement, a provider's signature proves what the provider said, not that the person did it honestly; the trust tier reflects that.
* Replay protection is the nonce table; freshness limits keep the table useful and stop old statements being reused.
