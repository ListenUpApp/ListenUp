module.exports = {
    content: [
        "./internal/web/view/**/*.templ",  // Original templ files
        "./internal/web/view/**/*.go",     // Generated Go files
        "./internal/web/view/layouts/*.templ",
        "./internal/web/view/layouts/*.go",
        "./internal/web/view/pages/*.templ",
        "./internal/web/view/pages/*.go"
    ],
    theme: {
        fontFamily: {
            'sans': ['poppins', 'sans-serif'],
        },
        extend: {},
    },
    plugins: [
        require('daisyui'),
    ],
    daisyui: {
        themes: [
            {
                listenup: {
                    "primary": "#F05A3B",
                    "secondary": "#384252",
                    "accent": "#8292AA",
                    "base-100": "#F6F0F0"
                }
            }
        ],
        styled: false,
    }
}