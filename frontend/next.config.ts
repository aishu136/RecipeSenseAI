import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  images: {
    // Meal plan recipe photos from the Spoonacular API
    remotePatterns: [
      { protocol: "https", hostname: "spoonacular.com" },
      { protocol: "https", hostname: "img.spoonacular.com" },
    ],
  },
};

export default nextConfig;
