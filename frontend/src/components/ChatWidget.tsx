"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { MicButton } from "@/components/MicButton";
import { deleteResource, parseRecipe, postJson, signInHref, type ChatReply, type ShoppingItem } from "@/lib/api";
import { getLocale, useI18n, type Translate } from "@/lib/i18n";
import { ingredientText } from "@/lib/recipe";
import { addToCart } from "@/lib/shoppingCart";
import { askToSubscribe } from "@/lib/signInDialog";
import { isUnauthorized, needSignIn } from "@/lib/useCurrentUser";

type Message = {
  role: "user" | "assistant";
  text: string;
  recipe?: string | null;
  shoppingList?: ShoppingItem[] | null;
  needs?: ChatReply["needs"];
  needsMessage?: string | null;
  error?: boolean;
};

type Saved = { conversationId: string; messages: Message[] };

const STORAGE_KEY = "recipetool.chat";
const MAX_LENGTH = 1000;

// The conversation, kept for this browser tab so it survives page reloads
function load(): Saved {
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? "null") as Saved | null;
    if (saved?.conversationId && Array.isArray(saved.messages)) return saved;
  } catch {
    // storage unavailable or unreadable: start afresh
  }
  return { conversationId: crypto.randomUUID(), messages: [] };
}

function save(saved: Saved) {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(saved));
  } catch {
    // storage unavailable: the conversation just isn't kept across reloads
  }
}

// The site's chat assistant: a button in the corner that opens a chat panel
export function ChatWidget() {
  const pathname = usePathname();
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [chat, setChat] = useState<Saved | null>(null);
  const [input, setInput] = useState("");
  const [pending, setPending] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const messages = chat?.messages ?? [];

  function update(next: Saved) {
    setChat(next);
    save(next);
  }

  function toggle() {
    if (!chat) setChat(load());
    setOpen((o) => !o);
  }

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  // Keep the newest message in view
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages.length, pending, open]);

  async function send(e?: React.FormEvent) {
    e?.preventDefault();
    const text = input.trim();
    if (!text || pending || !chat) return;
    const asked: Saved = { ...chat, messages: [...chat.messages, { role: "user", text }] };
    update(asked);
    setInput("");
    setPending(true);
    try {
      const reply = await postJson<ChatReply>("/chat", {
        conversationId: chat.conversationId,
        message: text,
        language: getLocale(),
      });
      update({
        ...asked,
        messages: [
          ...asked.messages,
          {
            role: "assistant",
            text: reply.reply,
            recipe: reply.recipe,
            shoppingList: reply.shoppingList,
            needs: reply.needs,
            needsMessage: reply.needsMessage,
          },
        ],
      });
    } catch (err) {
      if (isUnauthorized(err)) needSignIn(err, t("chat.signInToChat"));
      const message = err instanceof Error ? err.message : String(err);
      update({ ...asked, messages: [...asked.messages, { role: "assistant", text: message, error: true }] });
    } finally {
      setPending(false);
    }
  }

  async function newChat() {
    if (!chat) return;
    const old = chat.conversationId;
    update({ conversationId: crypto.randomUUID(), messages: [] });
    try {
      await deleteResource(`/chat/${old}`);
    } catch {
      // it expires by itself after a day anyway
    }
    inputRef.current?.focus();
  }

  return (
    <>
      {open && (
        <section
          role="dialog"
          aria-label={t("chat.dialog")}
          onKeyDown={(e) => e.key === "Escape" && setOpen(false)}
          className="fixed bottom-20 right-4 z-20 flex h-[min(36rem,calc(100dvh-7rem))] w-[min(24rem,calc(100vw-2rem))] flex-col overflow-hidden rounded-2xl border border-border bg-surface shadow-2xl"
        >
          <header className="flex items-center justify-between gap-2 border-b border-border px-4 py-3">
            <div>
              <h2 className="text-sm font-semibold">Chef</h2>
              <p className="text-xs text-muted">{t("chat.subtitle")}</p>
            </div>
            <div className="flex gap-1">
              <button
                type="button"
                onClick={newChat}
                className="rounded-lg px-2 py-1 text-xs font-medium text-muted hover:bg-surface-2 hover:text-foreground"
              >
                {t("chat.new")}
              </button>
              <button
                type="button"
                onClick={() => setOpen(false)}
                aria-label={t("chat.close")}
                className="rounded-lg px-2 py-1 text-sm text-muted hover:bg-surface-2 hover:text-foreground"
              >
                ✕
              </button>
            </div>
          </header>

          <div ref={listRef} className="flex-1 space-y-3 overflow-y-auto px-4 py-3" aria-live="polite">
            {[{ role: "assistant", text: t("chat.greeting") } as Message, ...messages].map((message, i) => (
              <ChatMessage key={i} message={message} pathname={pathname} t={t} />
            ))}
            {pending && <p className="text-sm text-muted">{t("chat.thinking")}</p>}
          </div>

          <form onSubmit={send} className="flex items-end gap-2 border-t border-border p-3">
            <label htmlFor="chat-input" className="sr-only">
              {t("chat.message")}
            </label>
            <textarea
              id="chat-input"
              ref={inputRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) send(e);
              }}
              maxLength={MAX_LENGTH}
              rows={2}
              placeholder={t("chat.placeholder")}
              className="max-h-32 flex-1 resize-none rounded-lg border border-border bg-surface px-3 py-2 text-sm outline-none focus:border-accent focus:ring-2 focus:ring-accent/20"
            />
            <MicButton
              label={t("mic.chat")}
              onText={(spoken) => setInput((current) => (current.trim() ? `${current.trim()} ${spoken}` : spoken))}
            />
            <button
              type="submit"
              disabled={pending || !input.trim()}
              className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white transition hover:bg-accent-strong disabled:opacity-60"
            >
              {t("chat.send")}
            </button>
          </form>
        </section>
      )}

      <button
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-label={open ? t("chat.closeButton") : t("chat.openButton")}
        className="fixed bottom-4 right-4 z-20 flex items-center gap-2 rounded-full bg-accent px-4 py-3 text-sm font-semibold text-white shadow-lg transition hover:bg-accent-strong"
      >
        <span aria-hidden>💬</span>
        {open ? t("chat.closeShort") : t("chat.ask")}
      </button>
    </>
  );
}

function ChatMessage({ message, pathname, t }: { message: Message; pathname: string; t: Translate }) {
  if (message.role === "user") {
    return (
      <div className="ml-8 rounded-2xl rounded-br-sm bg-accent px-3 py-2 text-sm text-white">
        <p className="whitespace-pre-wrap break-words">{message.text}</p>
      </div>
    );
  }
  return (
    <div className="mr-4 flex flex-col gap-2">
      <div
        className={`rounded-2xl rounded-bl-sm px-3 py-2 text-sm ${
          message.error ? "bg-danger/10 text-danger" : "bg-surface-2"
        }`}
      >
        <ChatText text={message.text} />
      </div>
      {message.recipe && <ChatRecipe text={message.recipe} t={t} />}
      {message.shoppingList && message.shoppingList.length > 0 && (
        <ChatShoppingList items={message.shoppingList} recipe={message.recipe ?? null} t={t} />
      )}
      {message.needs === "signIn" && (
        <a
          href={signInHref(pathname)}
          className="self-start rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-strong"
        >
          {t("nav.signIn")}
        </a>
      )}
      {message.needs === "subscribe" && (
        <button
          type="button"
          onClick={() => askToSubscribe(message.needsMessage ?? t("home.choosePlanToKeepGoing"))}
          className="self-start rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-strong"
        >
          {t("home.seePlans")}
        </button>
      )}
    </div>
  );
}

// A recipe in the chat: compact, with the method folded away
function ChatRecipe({ text, t }: { text: string; t: Translate }) {
  const recipe = parseRecipe(text);
  if (!recipe) return null;
  return (
    <div className="rounded-xl border border-border p-3 text-sm">
      <div className="flex items-start justify-between gap-2">
        <h3 className="font-semibold">{recipe.recipeName || t("recipe.untitled")}</h3>
        {recipe.calories != null && (
          <span className="shrink-0 text-xs text-muted">{t("recipe.kcal", { calories: recipe.calories })}</span>
        )}
      </div>
      <ul className="mt-2 list-disc space-y-0.5 pl-5">
        {recipe.ingredients.map((item, i) => (
          <li key={i}>{ingredientText(item)}</li>
        ))}
      </ul>
      {recipe.instructions.length > 0 && (
        <details className="mt-2">
          <summary className="cursor-pointer text-xs font-semibold text-accent">{t("recipe.method")}</summary>
          <ol className="mt-1 list-decimal space-y-1 pl-5">
            {recipe.instructions.map((step, i) => (
              <li key={i}>{step}</li>
            ))}
          </ol>
        </details>
      )}
      <button
        type="button"
        onClick={() => addToCart({ name: recipe.recipeName, recipe: text, servings: null })}
        className="mt-2 text-xs font-semibold text-accent hover:underline"
      >
        🛒 {t("recipe.addToList")}
      </button>
    </div>
  );
}

// A shopping list the assistant made
function ChatShoppingList({ items, recipe, t }: { items: ShoppingItem[]; recipe: string | null; t: Translate }) {
  return (
    <div className="rounded-xl border border-border p-3 text-sm">
      <h3 className="font-semibold">🛒 {t("shopping.title")}</h3>
      <ul className="mt-2 space-y-0.5" aria-label={t("shopping.title")}>
        {items.map((item) => (
          <li key={item.text}>☐ {item.text}</li>
        ))}
      </ul>
      <Link href="/shopping-list" className="mt-2 inline-block text-xs font-semibold text-accent hover:underline">
        {recipe ? t("chat.openList") : t("nav.shopping")}
      </Link>
    </div>
  );
}

/**
 * The assistant's text: paragraphs, "- " lists, **bold**, and markdown links
 * to pages of this site only (anything else stays plain text).
 */
export function ChatText({ text }: { text: string }) {
  const blocks: ReactNode[] = [];
  let list: string[] = [];
  const flush = () => {
    if (list.length) {
      blocks.push(
        <ul key={blocks.length} className="list-disc space-y-0.5 pl-5">
          {list.map((item, i) => (
            <li key={i}>{inline(item)}</li>
          ))}
        </ul>,
      );
      list = [];
    }
  };
  for (const line of text.split(/\r?\n/)) {
    const item = line.match(/^\s*[-*•]\s+(.*)$/);
    if (item) {
      list.push(item[1]);
    } else {
      flush();
      if (line.trim()) blocks.push(<p key={blocks.length}>{inline(line)}</p>);
    }
  }
  flush();
  return <div className="space-y-2 break-words">{blocks}</div>;
}

function inline(text: string): ReactNode[] {
  const parts: ReactNode[] = [];
  const pattern = /\[([^\]]+)\]\(([^)\s]+)\)|\*\*([^*]+)\*\*/g;
  let last = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index > last) parts.push(text.slice(last, match.index));
    const [whole, label, href, bold] = match;
    if (bold) {
      parts.push(<strong key={match.index}>{bold}</strong>);
    } else if (href.startsWith("/") && !href.startsWith("//")) {
      parts.push(
        <Link key={match.index} href={href} className="font-medium text-accent underline">
          {label}
        </Link>,
      );
    } else {
      parts.push(<Fragment key={match.index}>{label}</Fragment>);
    }
    last = match.index + whole.length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return parts;
}
