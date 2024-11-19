module.exports = {
    content: [
        "./web/view/**/*.templ",  // Original templ files
        "./web/view/**/*.go",     // Generated Go files
        "./web/view/layouts/*.templ",
        "./web/view/layouts/*.go",
        "./web/view/pages/*.templ",
        "./web/view/pages/*.go"
    ],
    theme: {
        extend: {},
    },
    plugins: [],
}