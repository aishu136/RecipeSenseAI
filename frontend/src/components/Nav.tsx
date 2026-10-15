"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { signInHref } from "@/lib/api";
import { useCurrentUser } from "@/lib/useCurrentUser";

const LINKS = [
  { href: "/", label: "Recipe" },
  { href: "/meal-plan", label: "Meal plan" },
  { href: "/autonomous", label: "Agent" },
  { href: "/profile", label: "Profile" },
  { href: "/billing", label: "Plan" },
] as const;

export function Nav() {
  const pathname = usePathname();
  return (
    <div className="flex flex-wrap items-center gap-3">
      <nav className="flex gap-1">
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
            {label}
          </Link>
        ))}
      </nav>
      <Account pathname={pathname} />
    </div>
  );
}

// Who is signed in, with sign-in and sign-out links (plain links: they're route handlers)
function Account({ pathname }: { pathname: string }) {
  const user = useCurrentUser();
  if (user.status === "loading") return null;
  if (user.status === "signedOut") {
    return (
      <a href={signInHref(pathname)} className="text-sm font-medium text-accent hover:underline">
        Sign in
      </a>
    );
  }
  return (
    <span className="text-sm text-muted">
      {user.userId} ·{" "}
      <a href="/auth/logout" className="font-medium text-accent hover:underline">
        Sign out
      </a>
    </span>
  );
}
