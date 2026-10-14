"use client";

import { useEffect, useState } from "react";
import { Button, Card, ErrorBox, Field, Input, PageHeader, Textarea } from "@/components/ui";
import {
  ApiError,
  deleteResource,
  getJson,
  postJson,
  putJson,
  splitList,
  userPath,
  type DietaryRestrictions,
  type Preference,
  type SimilarUser,
} from "@/lib/api";
import { useUserId } from "@/lib/useUserId";

type Profile = {
  userId: string;
  restrictions: DietaryRestrictions;
  preferences: Preference[];
  similar: SimilarUser[];
};

type State =
  | { status: "loading" }
  | { status: "signedOut" }
  | { status: "error"; message: string }
  | { status: "ready"; profile: Profile };

async function loadProfile(userId: string): Promise<Profile> {
  const [restrictions, preferences, similar] = await Promise.all([
    getJson<DietaryRestrictions>(userPath(userId, "restrictions")),
    getJson<Preference[]>(userPath(userId, "preferences")),
    getJson<SimilarUser[]>(userPath(userId, "similar")),
  ]);
  return { userId, restrictions, preferences, similar };
}

function message(err: unknown): string {
  if (err instanceof ApiError && err.status === 401) return "Your session has expired. Sign in again.";
  return err instanceof Error ? err.message : String(err);
}

const linkButton =
  "inline-flex items-center justify-center rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white transition hover:bg-accent-strong";

export default function ProfilePage() {
  const [, setUserId] = useUserId();
  const [state, setState] = useState<State>({ status: "loading" });

  // The profile of whoever is signed in (the /api proxy sends their token)
  useEffect(() => {
    let current = true;
    (async () => {
      try {
        const { userId } = await getJson<{ userId: string }>("/me");
        const profile = await loadProfile(userId);
        if (!current) return;
        // Recipes and meal plans use the same id, so they get these restrictions
        setUserId(userId);
        setState({ status: "ready", profile });
      } catch (err) {
        if (!current) return;
        const signedOut = err instanceof ApiError && err.status === 401;
        setState(signedOut ? { status: "signedOut" } : { status: "error", message: message(err) });
      }
    })();
    return () => {
      current = false;
    };
  }, [setUserId]);

  return (
    <>
      <PageHeader title="Your dietary profile">
        Saved restrictions apply to every recipe and meal plan made with your user id. Free-text preferences guide the
        recipes they&apos;re relevant to.
      </PageHeader>

      {state.status === "loading" && <Card className="text-sm text-muted">Loading your profile…</Card>}

      {state.status === "signedOut" && (
        <Card className="flex max-w-md flex-col items-start gap-3">
          <p className="text-sm text-muted">Sign in to see and edit your dietary profile.</p>
          <a href="/auth/login?returnTo=/profile" className={linkButton}>
            Sign in
          </a>
        </Card>
      )}

      {state.status === "error" && <ErrorBox message={state.message} />}

      {state.status === "ready" && (
        <ProfileCards profile={state.profile} onChange={(profile) => setState({ status: "ready", profile })} />
      )}
    </>
  );
}

function ProfileCards({ profile, onChange }: { profile: Profile; onChange: (profile: Profile) => void }) {
  const { userId } = profile;
  return (
    <>
      <p className="mb-6 text-sm text-muted">
        Signed in as <span className="font-medium text-foreground">{userId}</span> ·{" "}
        <a href="/auth/logout" className="font-medium text-accent hover:underline">
          Sign out
        </a>
      </p>
      <div className="grid gap-6 lg:grid-cols-2">
        <RestrictionsCard
          userId={userId}
          saved={profile.restrictions}
          onSaved={async () => onChange(await loadProfile(userId))}
        />
        <PreferencesCard
          userId={userId}
          preferences={profile.preferences}
          onChange={(preferences) => onChange({ ...profile, preferences })}
        />
        <SimilarUsersCard
          similar={profile.similar}
          hasRestrictions={profile.restrictions.allergies.length + profile.restrictions.avoid.length > 0}
        />
      </div>
    </>
  );
}

function RestrictionsCard({
  userId,
  saved,
  onSaved,
}: {
  userId: string;
  saved: DietaryRestrictions;
  onSaved: () => Promise<void>;
}) {
  const [allergies, setAllergies] = useState(saved.allergies.join(", "));
  const [avoid, setAvoid] = useState(saved.avoid.join(", "));
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setStatus(null);
    try {
      await putJson<DietaryRestrictions>(userPath(userId, "restrictions"), {
        allergies: splitList(allergies),
        avoid: splitList(avoid),
      });
      await onSaved();
      setStatus("Saved");
    } catch (err) {
      setError(message(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card>
      <h2 className="mb-1 font-semibold">Restrictions</h2>
      <p className="mb-4 text-sm text-muted">Saved in Redis and never left out of a request with your user id.</p>
      <form onSubmit={save} className="flex flex-col gap-4">
        <Field label="Allergies" hint="Comma-separated; never in a recipe, nor anything made from them">
          <Input value={allergies} onChange={(e) => setAllergies(e.target.value)} placeholder="peanut, dairy" />
        </Field>
        <Field label="Avoid" hint="Comma-separated ingredients you don't eat">
          <Input value={avoid} onChange={(e) => setAvoid(e.target.value)} placeholder="mushroom" />
        </Field>
        {error && <ErrorBox message={error} />}
        <div className="flex items-center gap-3">
          <Button type="submit" loading={saving}>
            Save restrictions
          </Button>
          {status && <span className="text-sm text-muted">{status}</span>}
        </div>
      </form>
    </Card>
  );
}

function PreferencesCard({
  userId,
  preferences,
  onChange,
}: {
  userId: string;
  preferences: Preference[];
  onChange: (preferences: Preference[]) => void;
}) {
  const [text, setText] = useState("");
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setAdding(true);
    setError(null);
    try {
      const added = await postJson<Preference>(userPath(userId, "preferences"), { text });
      onChange([...preferences, added]);
      setText("");
    } catch (err) {
      setError(message(err));
    } finally {
      setAdding(false);
    }
  }

  async function remove(preference: Preference) {
    setError(null);
    try {
      await deleteResource(userPath(userId, `preferences/${encodeURIComponent(preference.id)}`));
      onChange(preferences.filter((p) => p.id !== preference.id));
    } catch (err) {
      setError(message(err));
    }
  }

  return (
    <Card>
      <h2 className="mb-1 font-semibold">Preferences</h2>
      <p className="mb-4 text-sm text-muted">
        Anything about how you like to eat. Stored in OpenSearch; the ones closest to each request go into its prompt.
      </p>
      {preferences.length > 0 ? (
        <ul className="mb-4 flex flex-col gap-2">
          {preferences.map((preference) => (
            <li
              key={preference.id}
              className="flex items-start justify-between gap-3 rounded-lg bg-surface-2 px-3 py-2 text-sm"
            >
              <span className="min-w-0 break-words">{preference.text}</span>
              <button
                type="button"
                onClick={() => remove(preference)}
                className="shrink-0 text-xs font-medium text-muted hover:text-danger"
                aria-label={`Delete preference: ${preference.text}`}
              >
                Delete
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mb-4 text-sm text-muted">No preferences yet.</p>
      )}
      <form onSubmit={add} className="flex flex-col gap-3">
        <Textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={2}
          maxLength={500}
          placeholder="Low sugar; I love spicy food and quick weeknight dinners"
          required
        />
        {error && <ErrorBox message={error} />}
        <div>
          <Button type="submit" loading={adding}>
            Add preference
          </Button>
        </div>
      </form>
    </Card>
  );
}

function SimilarUsersCard({ similar, hasRestrictions }: { similar: SimilarUser[]; hasRestrictions: boolean }) {
  return (
    <Card className="lg:col-span-2">
      <h2 className="mb-1 font-semibold">Users with similar restrictions</h2>
      <p className="mb-4 text-sm text-muted">Found by similarity search over everyone&apos;s saved restrictions.</p>
      {similar.length > 0 ? (
        <ul className="grid gap-2 sm:grid-cols-2">
          {similar.map((user) => (
            <li key={user.userId} className="rounded-lg bg-surface-2 px-3 py-2 text-sm">
              <div className="flex justify-between gap-3">
                <span className="font-medium">{user.userId}</span>
                <span className="text-muted">{Math.round(user.similarity * 100)}% similar</span>
              </div>
              <div className="mt-1 text-muted">
                {[
                  user.allergies.length > 0 && `Allergic to ${user.allergies.join(", ")}`,
                  user.avoid.length > 0 && `avoids ${user.avoid.join(", ")}`,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </div>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted">
          {hasRestrictions ? "No other users have saved restrictions yet." : "Save some restrictions to find users like you."}
        </p>
      )}
    </Card>
  );
}
