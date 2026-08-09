import typography from "@tailwindcss/typography";
import type { Config } from "tailwindcss";

export default {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        "foreground-muted": "hsl(var(--foreground-muted))",
        "foreground-subtle": "hsl(var(--foreground-subtle))",
        page: "hsl(var(--color-page))",
        surface: "hsl(var(--color-surface))",
        raised: "hsl(var(--color-raised))",
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--card))",
          foreground: "hsl(var(--card-foreground))",
        },
        success: {
          DEFAULT: "hsl(var(--success))",
          foreground: "hsl(var(--success-foreground))",
        },
        warning: {
          DEFAULT: "hsl(var(--warning))",
          foreground: "hsl(var(--warning-foreground))",
        },
        danger: {
          DEFAULT: "hsl(var(--danger))",
          foreground: "hsl(var(--danger-foreground))",
        },
        info: {
          DEFAULT: "hsl(var(--info))",
          foreground: "hsl(var(--info-foreground))",
        },
      },
      fontFamily: {
        sans: ["Inter", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "sans-serif"],
        display: ["Newsreader", "Georgia", "Times New Roman", "serif"],
      },
      fontSize: {
        display: ["var(--text-display)", { lineHeight: "1.15", letterSpacing: "-0.025em", fontWeight: "600" }],
        h1: ["var(--text-h1)", { lineHeight: "1.25", letterSpacing: "-0.02em", fontWeight: "600" }],
        h2: ["var(--text-h2)", { lineHeight: "1.3", letterSpacing: "-0.015em", fontWeight: "600" }],
        h3: ["var(--text-h3)", { lineHeight: "1.4", letterSpacing: "-0.01em", fontWeight: "500" }],
        body: ["var(--text-body)", { lineHeight: "1.6" }],
        caption: ["var(--text-caption)", { lineHeight: "1.5" }],
        micro: ["var(--text-micro)", { lineHeight: "1.4" }],
      },
      spacing: {
        section: "var(--space-section)",
      },
      borderRadius: {
        sm: "var(--radius-sm)",
        DEFAULT: "var(--radius)",
        md: "var(--radius)",
        lg: "var(--radius-lg)",
        xl: "var(--radius-xl)",
      },
      boxShadow: {
        subtle: "0 1px 2px 0 rgb(41 37 36 / 0.08), 0 1px 3px -1px rgb(41 37 36 / 0.06)",
        card: "0 2px 8px -2px rgb(41 37 36 / 0.12), 0 0 0 1px hsl(var(--border) / 0.55)",
        raised: "0 4px 16px -4px rgb(41 37 36 / 0.16), 0 0 0 1px hsl(var(--border) / 0.7)",
        glow: "0 0 20px -5px hsl(var(--primary) / 0.25)",
      },
      typography: {
        // Map the report `prose` theme onto the light 熊博士 design tokens.
        DEFAULT: {
          css: {
            maxWidth: "none",
            "--tw-prose-body": "hsl(var(--foreground-muted))",
            "--tw-prose-headings": "hsl(var(--foreground))",
            "--tw-prose-lead": "hsl(var(--foreground-muted))",
            "--tw-prose-links": "hsl(var(--primary))",
            "--tw-prose-bold": "hsl(var(--foreground))",
            "--tw-prose-counters": "hsl(var(--foreground-subtle))",
            "--tw-prose-bullets": "hsl(var(--primary) / 0.5)",
            "--tw-prose-hr": "hsl(var(--border))",
            "--tw-prose-quotes": "hsl(var(--foreground))",
            "--tw-prose-quote-borders": "hsl(var(--primary) / 0.5)",
            "--tw-prose-captions": "hsl(var(--foreground-subtle))",
            "--tw-prose-code": "hsl(var(--foreground))",
            "--tw-prose-pre-code": "hsl(var(--foreground))",
            "--tw-prose-pre-bg": "hsl(var(--color-raised))",
            "--tw-prose-th-borders": "hsl(var(--border))",
            "--tw-prose-td-borders": "hsl(var(--border))",
          },
        },
      },
    },
  },
  plugins: [typography],
} satisfies Config;
