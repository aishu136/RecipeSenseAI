"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LanguageSelect } from "@/components/LanguageSelect";
import { signInHref } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import type { MessageKey } from "@/lib/messages";
import { useCart } from "@/lib/shoppingCart";
import { useCurrentUser } from "@/lib/useCurrentUser";

const LINKS: { href: string; label: MessageKey }[] = [
  { href: "/", label: "nav.recipe" },
  { href: "/recipes", label: "nav.recipes" },
  { href: "/shopping-list", label: "nav.shopping" },
  { href: "/meal-plan", label: "nav.mealPlan" },
  { href: "/week", label: "nav.week" },
  { href: "/autonomous", label: "nav.agent" },
  { href: "/profile", label: "nav.profile" },
  { href: "/billing", label: "nav.plan" },
];

export function Nav() {
  const pathname = usePathname();
  const { t } = useI18n();
  const cart = useCart();
  return (
    <div className="flex flex-wrap items-center gap-3">
      <nav className="flex flex-wrap gap-1">
        {LINKS.map(({ href, label }) => (
          <Link
            key={href}
            href={href}
            className={`rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
              pathname === href
                ? "bg-accent text-white"
                : "text-muted hover:bg-surface-2 hover:text-foreground"
            }`}
          >
            {t(label)}
            {href === "/shopping-list" && cart.length > 0 && (
              <span className="ml-1 rounded-full bg-surface-2 px-1.5 text-xs text-foreground">{cart.length}</span>
            )}
          </Link>
        ))}
      </nav>
      <LanguageSelect />
      <Account pathname={pathname} />
    </div>
  );
}

// Who is signed in, with sign-in and sign-out links (plain links: they're route handlers)
function Account({ pathname }: { pathname: string }) {
  const user = useCurrentUser();
  const { t } = useI18n();
  if (user.status === "loading") return null;
  if (user.status === "signedOut") {
    return (
      <a href={signInHref(pathname)} className="text-sm font-medium text-accent hover:underline">
        {t("nav.signIn")}
      </a>
    );
  }
  return (
    <span className="text-sm text-muted">
      {user.userId} ·{" "}
      <a href="/auth/logout" className="font-medium text-accent hover:underline">
        {t("nav.signOut")}
      </a>
    </span>
  );
}
