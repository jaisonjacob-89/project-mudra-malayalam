#!/bin/bash
cd "$(dirname "$0")"
echo "📦 Staging all changes..."
git add .
echo "💬 Enter commit message (or press Enter for 'Quick update'):"
read msg
msg=${msg:-"Quick update"}
git commit -m "$msg"
echo "⬆️  Pushing to GitHub..."
git push
echo "✅ Done! Live at: https://htmlpreview.github.io/?https://github.com/jaisonjacob-89/project-mudra-malayalam/blob/main/preview.html"
