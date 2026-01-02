# SalmanBappi Manga Extensions 🚀

Manga extensions for **Komikku**, **Mihon**, and other Tachiyomi forks. Developed and maintained by **SalmanBappi (@SBManga)**.

---

## 📂 Extensions Included

| Extension | Language | Version Code | Source ID | Website |
| :--- | :---: | :---: | :--- | :--- |
| **Comix** | EN | 107+ | `7537715367149829912` | [comix.to](https://comix.to) |
| **Like Manga** | EN | 102+ | `411833355147795520` | [likemanga.ink](https://likemanga.ink) |

---

## 🛠️ How to Add the Repository

To install these extensions, copy and paste the following URL into your app's extension repository settings:

**Raw JSON URL:**
```
https://raw.githubusercontent.com/salmanbappi/salmanbappi-manga-extension/main/index.min.json
```

**GitHub Pages URL:**
```
https://salmanbappi.github.io/salmanbappi-manga-extension/index.min.json
```

---

## ⚙️ Technical Maintenance (Important)

To prevent the **"Obsolete"** status in Komikku/Mihon, the following configurations must remain consistent:

### 1. Signature Fingerprint
The repository index and APKs must be signed with the same key. The fingerprint used in `generate_repo.py` and `merge-repo.py` is:
`212199045691887b32eb2397f167f4b7d53a73131119975df9914595bc95880a`

### 2. Stable Source IDs
Do not change these IDs, or users will lose their tracking/history:
- **Comix:** `7537715367149829912`
- **Like Manga:** `411833355147795520`

### 3. Automated Builds
- **Version Bumping:** The GitHub Actions workflow automatically increments `extVersionCode` on every push.
- **Branding:** Official extensions are built with custom icons processed to **432x432px**.

---

## 🛡️ Disclaimer

This project is not affiliated with the content providers. It is an independent extension project. For support or issues, please open a request in this repository.

---
*Maintained by SalmanBappi*