"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";

// The Web Speech API's recognition, which TypeScript's DOM types leave out
// (Chrome and Edge have it as webkitSpeechRecognition; Firefox has none)
type RecognitionResult = { isFinal: boolean; 0: { transcript: string } };
type RecognitionEvent = { resultIndex: number; results: ArrayLike<RecognitionResult> };

export type Recognition = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: RecognitionEvent) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
};

type RecognitionConstructor = new () => Recognition;

function recognitionClass(): RecognitionConstructor | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as { SpeechRecognition?: RecognitionConstructor; webkitSpeechRecognition?: RecognitionConstructor };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

const noSubscribe = () => () => {};

// Whether the browser can listen; false while server rendering, so hydration matches
export function useCanListen(): boolean {
  return useSyncExternalStore(noSubscribe, () => recognitionClass() !== null, () => false);
}

// Whether the browser can read aloud
export function useCanSpeak(): boolean {
  return useSyncExternalStore(noSubscribe, () => typeof window !== "undefined" && "speechSynthesis" in window, () => false);
}

export function createRecognition(lang: string, continuous: boolean): Recognition | null {
  const Recognition = recognitionClass();
  if (!Recognition) return null;
  const recognition = new Recognition();
  recognition.lang = lang;
  recognition.continuous = continuous;
  recognition.interimResults = false;
  return recognition;
}

// The final text in a recognition event
export function finalText(event: RecognitionEvent): string {
  let text = "";
  for (let i = event.resultIndex; i < event.results.length; i++) {
    if (event.results[i].isFinal) text += event.results[i][0].transcript;
  }
  return text.trim();
}

/**
 * Dictation: start() listens for one phrase and passes its text to onText.
 * error is set when the microphone is blocked or nothing was heard.
 */
export function useDictation(lang: string, onText: (text: string) => void) {
  const [listening, setListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recognition = useRef<Recognition | null>(null);
  const callback = useRef(onText);

  useEffect(() => {
    callback.current = onText;
  }, [onText]);

  useEffect(() => () => recognition.current?.abort(), []);

  function start() {
    recognition.current?.abort();
    const r = createRecognition(lang, false);
    if (!r) return;
    r.onresult = (event) => {
      const text = finalText(event);
      if (text) callback.current(text);
    };
    r.onerror = (event) => setError(event.error);
    r.onend = () => setListening(false);
    recognition.current = r;
    setError(null);
    setListening(true);
    try {
      r.start();
    } catch {
      setListening(false);
    }
  }

  function stop() {
    recognition.current?.stop();
    setListening(false);
  }

  return { listening, error, start, stop };
}

// Reads text aloud, interrupting anything still being read
export function speak(text: string, lang: string) {
  if (typeof window === "undefined" || !("speechSynthesis" in window)) return;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = lang;
  window.speechSynthesis.speak(utterance);
}

export function stopSpeaking() {
  if (typeof window !== "undefined" && "speechSynthesis" in window) window.speechSynthesis.cancel();
}
