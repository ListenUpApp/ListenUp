module.exports = {
  content: [
    "./internal/web/view/**/*.templ", // Original templ files
    "./internal/web/view/**/*.go", // Generated Go files
    "./internal/web/view/layouts/*.templ",
    "./internal/web/view/layouts/*.go",
    "./internal/web/view/pages/*.templ",
    "./internal/web/view/pages/*.go",
  ],
  theme: {
    fontFamily: {
      sans: ["poppins", "sans-serif"],
    },

    extend: {
      colors: {
        primary: {
          bg: "#F6F0F0",
          50: "#FFFCFC",
          100: "#FEF5F3",
          200: "#FDE8E4",
          300: "#FBD6CE",
          400: "#F9BFB3",
          500: "#F7A292",
          600: "#F4816A",
          700: "#F05B3D",
          800: "#97230B",
          900: "#491106",
          950: "#2F0B04",
        },
        secondary: {
          50: "#FCFCFD",
          100: "#F1F3F6",
          200: "#E0E4EA",
          300: "#C8CED9",
          400: "#A8B3C4",
          500: "#8292AA",
          600: "#5B6B86",
          700: "#384252",
          800: "#242B35",
          900: "#191D24",
          950: "#15181E",
        },
      },
    },
  },
  plugins: [require("@tailwindcss/forms")],
};
