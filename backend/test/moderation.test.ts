import { describe, expect, it } from "vitest";
import { checkText } from "../src/moderation/text.js";

describe("profanity filter", () => {
  it.each([
    "fuck this",
    "FUCK",
    "this is shit",
    "what a b1tch",
    "f.u.c.k off",
    "f u c k",
    "sh!t",
    "a$$hole",
    "fuuuuuck",
    "motherfucker",
    "you are a c u n t",
    "n1gger",
    "stop being a r3tard",
    "bullsh1t goal",
    "Fück",
    "sluuuut",
  ])("blocks: %s", (text) => {
    expect(checkText(text).ok, text).toBe(false);
  });

  it.each([
    "Do 20 squats every day",
    "Walk 8000 steps a day",
    "Study for 2 hours",
    "No Instagram after 10pm",
    "Scunthorpe marathon training",
    "Read Dickens for 20 minutes",
    "classic assassin movies, no phone",
    "Cockpit checklist practice",
    "Drink 8 glasses of water",
    "Wake up by 5:30am",
    "Pass the class exam",
    "Bass guitar practice for 45 minutes",
    "grass cutting 30 minutes",
    "Meditate for 10 minutes",
    "Learn Swahili and Hausa",
    "Hit 10k steps",
    "Take a shitake mushroom cooking class",
    "assess my budget",
    "Run 5 km by the beach",
    "Be at the gym for 45 minutes",
    "pushups 15 times",
  ])("allows: %s", (text) => {
    expect(checkText(text).ok, text).toBe(true);
  });

  it("is not fooled by hidden characters", () => {
    expect(checkText("fu​ck").ok).toBe(false);
    expect(checkText("sh‮it").ok).toBe(false);
  });

  it("handles empty and very long text without trouble", () => {
    expect(checkText("").ok).toBe(true);
    expect(checkText("a ".repeat(5000)).ok).toBe(true);
  });
});
