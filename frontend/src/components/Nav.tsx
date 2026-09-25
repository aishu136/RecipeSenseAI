"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/", label: "Recipe" },
  { href: "/meal-plan", label: "Meal plan" },
  { href: "/autonomous", label: "Agent" },
] as const;

export function Nav() {
  const pathname = usePathname();
  return (
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
  );
}
