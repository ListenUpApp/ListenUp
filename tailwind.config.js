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
        extend: {},
    },
    plugins: [],
}