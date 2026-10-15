import type { Page } from "@playwright/test";

// Fakes the Web Speech API in the browser: say() delivers a phrase to the
// recognition that's listening, and spoken() returns what was read aloud.
export async function fakeSpeech(page: Page) {
  await page.addInitScript(() => {
    type Fake = {
      lang: string;
      continuous: boolean;
      started: boolean;
      onresult: ((event: unknown) => void) | null;
      onend: (() => void) | null;
    };
    const w = window as unknown as {
      __recognitions: Fake[];
      __spoken: { text: string; lang: string }[];
      __say: (text: string) => void;
      SpeechRecognition: unknown;
    };
    w.__recognitions = [];
    w.__spoken = [];
    class FakeRecognition {
      lang = "";
      continuous = false;
      interimResults = false;
      started = false;
      onresult: ((event: unknown) => void) | null = null;
      onerror: ((event: unknown) => void) | null = null;
      onend: (() => void) | null = null;
      constructor() {
        w.__recognitions.push(this as unknown as Fake);
      }
      start() {
        this.started = true;
      }
      stop() {
        this.started = false;
        this.onend?.();
      }
      abort() {
        this.started = false;
      }
    }
    w.SpeechRecognition = FakeRecognition;
    w.__say = (text: string) => {
      const listening = [...w.__recognitions].reverse().find((r) => r.started);
      if (!listening) throw new Error("Nothing is listening");
      listening.onresult?.({ resultIndex: 0, results: [{ isFinal: true, 0: { transcript: text } }] });
    };
    window.speechSynthesis.speak = (utterance: SpeechSynthesisUtterance) => {
      w.__spoken.push({ text: utterance.text, lang: utterance.lang });
    };
    window.speechSynthesis.cancel = () => {};
  });
}

export async function say(page: Page, text: string) {
  await page.evaluate((phrase) => (window as unknown as { __say: (t: string) => void }).__say(phrase), text);
}

export async function spoken(page: Page): Promise<{ text: string; lang: string }[]> {
  return page.evaluate(() => (window as unknown as { __spoken: { text: string; lang: string }[] }).__spoken);
}

// The language the newest recognition listens in
export async function listeningLang(page: Page): Promise<string> {
  return page.evaluate(() => {
    const all = (window as unknown as { __recognitions: { lang: string }[] }).__recognitions;
    return all[all.length - 1]?.lang ?? "";
  });
}
