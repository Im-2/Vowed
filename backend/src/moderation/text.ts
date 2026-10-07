/**
 * Basic text moderation for goals that other people will see. It is deliberately simple and explainable:
 *  - the text is normalised (case, accents, look-alike digits and symbols, separators between letters, stretched letters);
 *  - whole words are compared with a short list, so innocent words that merely contain a bad one ("class", "Scunthorpe", "Dickens") pass;
 *  - a few long, unambiguous stems are also caught inside longer words.
 * It will miss creative spellings and will sometimes be too strict; reports and hiding (see explore) cover the rest.
 */

// Whole words (after normalisation). Kept short on purpose: this is a first line of defence, not a dictionary.
const WORDS = new Set([
  "fuck", "fucks", "fucker", "fucking", "fucked", "motherfucker", "shit", "shits", "shitty", "bullshit", "bitch", "bitches", "asshole", "assholes",
  "cunt", "cunts", "dick", "dicks", "dickhead", "cock", "pussy", "bastard", "whore", "slut", "sluts", "twat", "wanker", "prick",
  "nigger", "niggers", "nigga", "niggas", "faggot", "faggots", "fag", "retard", "retards", "retarded", "spic", "chink", "kike", "tranny",
  "rapist", "rape", "raping", "kys", "porn", "porno", "nazi", "hitler",
]);

// Stems long and specific enough to be caught even inside a longer word. Short ones ("shit", "cunt") are NOT here: they would block
// innocent words ("Scunthorpe", "shiitake"); those are only matched as whole words.
const STEMS = ["motherfuck", "nigger", "faggot"];

const LOOKALIKE: Record<string, string> = { "0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "8": "b", "@": "a", "$": "s", "!": "i", "+": "t", "€": "e", "£": "l" };

export function normalizeForModeration(text: string): string {
  let t = text.normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase();
  t = t.replace(/[​-‏‪-‮⁠-⁩﻿]/g, "");
  t = [...t].map((c) => LOOKALIKE[c] ?? c).join("");
  return t;
}

/** The words of a text as a person would read them: letters separated by dots or dashes ("f.u.c.k") are joined. */
function tokens(text: string): string[] {
  const t = normalizeForModeration(text);
  // join runs of single letters separated by punctuation or spaces: "f u c k", "f.u.c.k", "f-u-c-k"
  const joined = t.replace(/\b([a-z])(?:[\s._*-]+(?=[a-z]\b))/g, "$1");
  return joined
    .split(/[^a-z]+/)
    .filter(Boolean)
    .map((w) => w.replace(/(.)\1{2,}/g, "$1$1")); // "fuuuuck" -> "fuuck"; then also try fully squeezed below
}

const squeeze = (w: string) => w.replace(/(.)\1+/g, "$1");

export interface ModerationResult {
  ok: boolean;
  /** the matched term, for logs and tests only; never shown back to the user */
  matched?: string;
}

/**
 * Letters spaced out one by one ("a c u n t", "f u c k"): the single letters in a row are joined and a listed word at the start or end of
 * the run counts (the run may begin with a real one-letter word such as "a").
 */
function spacedRun(text: string): ModerationResult {
  const parts = normalizeForModeration(text).split(/[^a-z]+/).filter(Boolean);
  let run = "";
  const flush = (): ModerationResult | null => {
    if (run.length >= 4) {
      for (const bad of WORDS) {
        if (bad.length >= 4 && (run === bad || run.endsWith(bad) || run.startsWith(bad))) return { ok: false, matched: bad };
      }
    }
    run = "";
    return null;
  };
  for (const w of parts) {
    if (w.length === 1) run += w;
    else {
      const hit = flush();
      if (hit) return hit;
    }
  }
  return flush() ?? { ok: true };
}

export function checkText(text: string): ModerationResult {
  const spaced = spacedRun(text);
  if (!spaced.ok) return spaced;
  for (const w of tokens(text)) {
    const s = squeeze(w);
    for (const list of [w, s]) {
      if (WORDS.has(list)) return { ok: false, matched: list };
    }
    // squeezed forms of listed words ("fuck" -> "fuck", "bitch" stays; "shit" -> "shit"): compare squeezed lists too
    for (const bad of WORDS) if (squeeze(bad) === s) return { ok: false, matched: bad };
    for (const stem of STEMS) if (s.length > stem.length && s.includes(squeeze(stem)) && s.length <= stem.length + 6) return { ok: false, matched: stem };
  }
  return { ok: true };
}

export const NOT_ALLOWED_MESSAGE = "Please rephrase: public goals cannot include that kind of language.";
