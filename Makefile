.PHONY: setup dev clean

# Setup all dependencies and tools
setup:
	@echo "Installing dependencies..."
	go mod tidy
	go install github.com/air-verse/air@latest
	go install github.com/a-h/templ/cmd/templ@latest
	npm install

# Start development server
dev:
	@echo "Starting development server..."
	air

# Clean generated files
clean:
	@echo "Cleaning generated files..."
	rm -rf tmp
	rm -f web/static/css/app.css
	find . -name "*_templ.go" -type f -delete