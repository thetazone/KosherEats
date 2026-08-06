import type { Config } from "tailwindcss";
import colors from "tailwindcss/colors";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        brand: {
          50: "#fff7ed",
          100: "#ffedd5",
          200: "#fed7aa",
          300: "#fdba74",
          400: "#fb923c",
          500: "#f97316", // primary orange
          600: "#ea580c",
          700: "#c2410c",
          800: "#9a3412",
          900: "#7c2d12",
          950: "#431407",
        },
        dark: {
          50: "#fafafa",
          100: "#f5f5f5",
          200: "#e5e5e5",
          300: "#d4d4d4",
          400: "#a3a3a3",
          500: "#737373",
          600: "#525252",
          700: "#404040",
          800: "#262626",
          850: "#1a1a1a",
          900: "#171717",
          950: "#0a0a0a",
        },
        // Semantic states — alias Tailwind hues so intent stays greppable.
        // Raw hue classes (red-*, green-*, …) are banned in TSX; see
        // docs/DESIGN_RUBRIC.md.
        success: colors.green,
        warning: colors.yellow,
        danger: colors.red,
        info: colors.blue,
        transit: colors.purple, // order picked_up / in-transit state
        // Kosher triad — dietary badges ONLY. A meat badge that isn't red
        // is a correctness bug.
        meat: colors.red,
        dairy: colors.blue,
        pareve: colors.green,
      },
      fontFamily: {
        sans: ["Inter", "system-ui", "sans-serif"],
      },
    },
  },
  plugins: [],
};

export default config;
