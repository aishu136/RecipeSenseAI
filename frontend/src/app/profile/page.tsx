"use client";

import { useEffect, useState } from "react";
import { Button, Card, ErrorBox, Field, Input, PageHeader, Textarea } from "@/components/ui";
import {
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

async function loadProfile(userId: string): Promise<Profile> {
  const [restrictions, preferences, similar] = await Promise.all([
    getJson<DietaryRestrictions>(userPath(userId, "restrictions")),
    getJson<Preference[]>(userPath(userId, "preferences")),
    getJson<SimilarUser[]>(userPath(userId, "similar")),
  ]);
  return { userId, restrictions, preferences, similar };
}

function message(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export default function ProfilePage() {
  const [userId, setUserId] = useUserId();
  const [profile, setProfile] = useState<Profile | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Loads the profile once the user id stops changing
  useEffect(() => {
    const id = userId.trim();
    if (!id) return;
    let current = true;
    const timer = setTimeout(async () => {
      try {
        const loaded = await loadProfile(id);
        if (current) {
          setProfile(loaded);
          setError(null);
        }
      } catch (err) {
        if (current) {
          setProfile(null);
          setError(message(err));
        }
      }
    }, 400);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [userId]);

  const id = userId.trim();

  return (
    <>
      <PageHeader title="Your dietary profile">
        Saved restrictions apply to every recipe and meal plan made with your user id. Free-text preferences guide the
        recipes they&apos;re relevant to.
      </PageHeader>

      <Card className="mb-6 max-w-sm">
        <Field label="User id">
          <Input value={userId} onChange={(e) => setUserId(e.target.value)} placeholder="u1" />
        </Field>
      </Card>

      {error && (
        <div className="mb-6">
          <ErrorBox message={error} />
        </div>
      )}

      {/* Only the loaded user's profile, never a previous one under the new id */}
      {id && profile?.userId === id && (
        <div className="grid gap-6 lg:grid-cols-2">
          <RestrictionsCard
            key={`${id}-restrictions`}
            userId={id}
            saved={profile.restrictions}
            onSaved={async () => setProfile(await loadProfile(id))}
          />
          <PreferencesCard
            key={`${id}-preferences`}
            userId={id}
            preferences={profile.preferences}
            onChange={(preferences) => setProfile({ ...profile, preferences })}
          />
          <SimilarUsersCard similar={profile.similar} hasRestrictions={profile.restrictions.allergies.length + profile.restrictions.avoid.length > 0} />
        </div>
      )}
      {!id && <Card className="text-sm text-muted">Enter a user id to see and edit its profile.</Card>}
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
