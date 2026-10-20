// Weeks for the weekly planner: each starts on a Monday, named by its date (YYYY-MM-DD)

function isoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

// A local date at noon, so daylight saving changes never move it to another day
function parse(iso: string): Date {
  const [year, month, day] = iso.split("-").map(Number);
  return new Date(year, month - 1, day, 12);
}

export function addDays(iso: string, days: number): string {
  const date = parse(iso);
  date.setDate(date.getDate() + days);
  return isoDate(date);
}

export function today(): string {
  return isoDate(new Date());
}

// The Monday of the week the day is in
export function mondayOf(iso: string): string {
  const daysSinceMonday = (parse(iso).getDay() + 6) % 7;
  return addDays(iso, -daysSinceMonday);
}

// 0 for Monday to 6 for Sunday
export function weekday(iso: string): number {
  return (parse(iso).getDay() + 6) % 7;
}

// e.g. "Mon 20 Oct", in the site's language
export function formatDay(iso: string, locale: string): string {
  return parse(iso).toLocaleDateString(locale, { weekday: "short", day: "numeric", month: "short" });
}
