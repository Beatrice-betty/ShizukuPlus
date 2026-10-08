# Translating Shizuku+

Thank you for helping! Strings live in `manager/src/main/res/values-<locale>/` (and `core/ui`).

## Machine-assisted drafts need review
Locales that lacked translations were filled with **AI-assisted drafts** in
`values-<locale>/strings_machine_translated.xml`. They are functional but unreviewed.

If you are a native speaker:
1. Review the strings in that file and fix any wording.
2. Move each corrected string into the locale's `strings.xml` and **delete it from the machine file** (a key must live in only one file or the build reports duplicate resources).
3. When the machine file is empty, delete it.
4. Open a pull request — small PRs are welcome, and you will be credited in the README.

## Rules
- Keep placeholders exactly (`%1$s`, `%d`, `^1`), HTML tags and escaped characters (`\'`, `\n`, `&amp;`).
- Don't translate brand/technical terms (Shizuku, Shizuku+, ADB, Wi-Fi, TCP, root, rish, Sentry).
- To list what is still untranslated for a locale: `python3 scripts/i18n/missing_keys.py <locale>`.
