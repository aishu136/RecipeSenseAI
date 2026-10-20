// Online grocery stores a shopping list item can be bought from. Neither has
// an API for adding to someone's cart, so each link opens the store's search
// for the item, where the user adds it to their cart and pays.
export const STORES = [
  { name: "Amazon", url: (item: string) => `https://www.amazon.in/s?k=${encodeURIComponent(item)}` },
  {
    name: "Flipkart",
    url: (item: string) => `https://www.flipkart.com/search?q=${encodeURIComponent(item)}&marketplace=GROCERY`,
  },
] as const;

/**
 * What the backend matches shopping list items and what's at home by
 * (Ingredient.key()): the name without its preparation ("onion, chopped") or
 * plural ending, in lower case.
 */
export function ingredientKey(name: string): string {
  let base = name.toLowerCase();
  const comma = base.indexOf(",");
  if (comma > 0) base = base.slice(0, comma);
  base = base.replace(/\(.*?\)/g, "").replace(/\s+/g, " ").trim();
  if (base.endsWith("oes") && base.length > 4) return base.slice(0, -2);
  if (base.endsWith("ies") && base.length > 4) return base.slice(0, -3) + "y";
  if (base.endsWith("s") && !base.endsWith("ss") && base.length > 3) return base.slice(0, -1);
  return base;
}
