"use client";

import { useEffect, useState } from "react";
import { translate, getLocale } from "@/lib/i18n";
import { Button, Card, ErrorBox, Field, Input, PageHeader, SignInPrompt, Textarea } from "@/components/ui";
import {
  deleteResource,
  getJson,
  postJson,
  putJson,
  splitList,
  userPath,
  type DietaryRestrictions,
  type Preference,
  type SimilarRestrictions,
} from "@/lib/api";
import { isUnauthorized, signedOut, useCurrentUser } from "@/lib/useCurrentUser";
import { useI18n } from "@/lib/i18n";

type Profile = {
  userId: string;
  restrictions: DietaryRestrictions;
  preferences: Preference[];
  similar: SimilarRestrictions;
};

// The loaded profile, or why it couldn't be loaded, for one user
type Loaded = { userId: string; profile: Profile } | { userId: string; error: string };

async function loadProfile(userId: string): Promise<Profile> {
  const [restrictions, preferences, similar] = await Promise.all([
    getJson<DietaryRestrictions>(userPath(userId, "restrictions")),
    getJson<Preference[]>(userPath(userId, "preferences")),
    getJson<SimilarRestrictions>(userPath(userId, "similar")),
  ]);
  return { userId, restrictions, preferences, similar };
}

// An error to show; a 401 means the session ended, so the page asks to sign in instead
function message(err: unknown): string {
  if (isUnauthorized(err)) {
    signedOut();
    return translate(getLocale(), "profile.sessionEnded");
  }
  return err instanceof Error ? err.message : String(err);
}

export default function ProfilePage() {
  const { t } = useI18n();
  const user = useCurrentUser();
  const userId = user.status === "signedIn" ? user.userId : null;
  const [loaded, setLoaded] = useState<Loaded | null>(null);

  // The signed-in user's profile (the /api proxy sends their token)
  useEffect(() => {
    if (!userId) return;
    let current = true;
    loadProfile(userId).then(
      (profile) => current && setLoaded({ userId, profile }),
      (err) => current && setLoaded({ userId, error: message(err) }),
    );
    return () => {
      current = false;
    };
  }, [userId]);

  // Only ever the signed-in user's own profile
  const mine = loaded && loaded.userId === userId ? loaded : null;

  return (
    <>
      <PageHeader title={t("profile.title")}>{t("profile.intro")}</PageHeader>

      {user.status === "signedOut" && <SignInPrompt message={t("profile.signIn")} returnTo="/profile" />}

      {userId && !mine && <Card className="text-sm text-muted">{t("profile.loading")}</Card>}

      {mine && "error" in mine && <ErrorBox message={mine.error} />}

      {mine && "profile" in mine && (
        <ProfileCards profile={mine.profile} onChange={(profile) => setLoaded({ userId: profile.userId, profile })} />
      )}
    </>
  );
}

function ProfileCards({ profile, onChange }: { profile: Profile; onChange: (profile: Profile) => void }) {
  const { userId } = profile;
  return (
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
  const { t } = useI18n();
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
      setStatus(t("profile.saved"));
    } catch (err) {
      setError(message(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card>
      <h2 className="mb-1 font-semibold">{t("profile.restrictions")}</h2>
      <p className="mb-4 text-sm text-muted">{t("profile.restrictionsHint")}</p>
      <form onSubmit={save} className="flex flex-col gap-4">
        <Field label={t("form.allergies")} hint={t("profile.allergiesHint")}>
          <Input value={allergies} onChange={(e) => setAllergies(e.target.value)} placeholder="peanut, dairy" />
        </Field>
        <Field label={t("form.avoid")} hint={t("form.avoidHint")}>
          <Input value={avoid} onChange={(e) => setAvoid(e.target.value)} placeholder="mushroom" />
        </Field>
        {error && <ErrorBox message={error} />}
        <div className="flex items-center gap-3">
          <Button type="submit" loading={saving}>
            {t("profile.saveRestrictions")}
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
  const { t } = useI18n();
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
      <h2 className="mb-1 font-semibold">{t("profile.preferences")}</h2>
      <p className="mb-4 text-sm text-muted">
        {t("profile.preferencesHint")}
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
                aria-label={t("profile.deletePreference", { text: preference.text })}
              >
                {t("recipe.delete")}
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mb-4 text-sm text-muted">{t("profile.noPreferences")}</p>
      )}
      <form onSubmit={add} className="flex flex-col gap-3">
        <Textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={2}
          maxLength={500}
          placeholder={t("profile.preferencePlaceholder")}
          required
        />
        {error && <ErrorBox message={error} />}
        <div>
          <Button type="submit" loading={adding}>
            {t("profile.addPreference")}
          </Button>
        </div>
      </form>
    </Card>
  );
}

function SimilarUsersCard({ similar, hasRestrictions }: { similar: SimilarRestrictions; hasRestrictions: boolean }) {
  const { t } = useI18n();
  return (
    <Card className="lg:col-span-2">
      <h2 className="mb-1 font-semibold">{t("profile.similar")}</h2>
      <p className="mb-4 text-sm text-muted">
        {t("profile.similarHint")}
      </p>
      {similar.users > 0 ? (
        <>
          <p className="text-sm">
            {t(similar.users === 1 ? "profile.similarOne" : "profile.similarMany", { count: similar.users })}
          </p>
          {similar.shared.length > 0 && (
            <ul className="mt-3 flex flex-wrap gap-2">
              {similar.shared.map((s) => (
                <li key={`${s.type}-${s.restriction}`} className="rounded-full bg-surface-2 px-3 py-1 text-sm">
                  {t(s.type === "allergy" ? "profile.allergicTo" : "profile.avoids", { restriction: s.restriction })}
                  <span className="text-muted"> · {t("profile.users", { count: s.users })}</span>
                </li>
              ))}
            </ul>
          )}
        </>
      ) : (
        <p className="text-sm text-muted">
          {hasRestrictions ? t("profile.noOthers") : t("profile.saveToFind")}
        </p>
      )}
    </Card>
  );
}
