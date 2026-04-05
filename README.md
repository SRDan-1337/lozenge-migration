# Lozenge Macro Migration — Confluence DC to Cloud

A ScriptRunner Groovy script that automatically converts broken **Lozenge macros** into **Advanced Cards** (Mosaic app) after a Confluence Data Center to Cloud migration.

---

## The Problem

After migrating from Confluence Data Center to Cloud using JCMA, pages that used the Lozenge macro are broken:

- ❌ Icons do not load
- ❌ Internal page links are not clickable
- ❌ All styling is lost

This is because the 'Lozenge' macro is a Data Center-only third-party app that Confluence Cloud does not recognise.

---

## The Solution

This script scans a Confluence page, finds every broken lozenge macro, and converts it into an **Advanced Card** (from the Mosaic app) — restoring titles, descriptions, and links automatically.

> ⚠️ **Icons cannot be migrated automatically.** The Advanced Cards app does not support image URLs. After running the script, icons must be re-added manually to each card. However, this takes a few clicks per card.

> ⚠️ **One page at a time.** The script fixes one page per run. For multiple pages, run it once per page changing the `PAGE_ID` each time.

---

## Before You Start

You need the following installed on your Confluence Cloud instance:

| Requirement | Why |
|---|---|
| **ScriptRunner for Confluence Cloud** | Runs the script |
| **Mosaic app** (Advanced Cards \| Mosaic) | Provides the Advanced Card macro that replaces the lozenge |
| **Admin access** | Required to read and write page content via the API |

## Apps:

[ScriptRunner for Confluence Cloud](https://marketplace.atlassian.com/apps/1215215/scriptrunner-for-confluence?hosting=cloud&tab=overview)

[Mosaic](https://marketplace.atlassian.com/apps/247/mosaic-content-formatting-macros-tabs-latex-html-templates?hosting=cloud&tab=overview)


 
To check if these apps are installed:
1. Go to your Confluence Cloud instance
2. Click **Apps** in the top navigation bar
3. Click **Manage your apps**
4. Search for **ScriptRunner** and **Mosaic** — both should appear as installed

If either app is missing, contact your Confluence administrator to have it installed before continuing.

---

## Finding Your 5 Configuration Values

Before running the script you need to collect 5 values specific to your instance.

---

### PAGE_ID
The ID of the page you want to fix. Find it in the page URL:
```
https://your-instance.atlassian.net/wiki/spaces/SPACE/pages/12345678/Page+Title
                                                              ^^^^^^^^
                                                         This is your PAGE_ID
```

---

### BASE_URL
Your Confluence instance URL — everything up to and including `.atlassian.net`:
```
https://your-company.atlassian.net
```
Do not include anything after `.atlassian.net`.

---

### CLOUD_ID and WORKSPACE_ID

These two values are found together using a single ScriptRunner snippet. You will need a Confluence page that already has an Advanced Card macro on it.

**If you do not have one yet:**
1. Create a new Confluence page anywhere (you can delete it afterwards)
2. Type `/Advanced Cards` in the page body
3. Insert the **Advanced Cards | Mosaic** macro, fill in any dummy title, and save
4. Copy the page ID from the URL (the number in the URL — see PAGE_ID above)

**Then run this snippet in Apps → ScriptRunner → Script Console**, replacing `12345678` with that page ID:

```groovy
def r = get("/wiki/api/v2/pages/12345678")
    .queryString("body-format", "storage")
    .asObject(Map)
def storage = ((r.body as Map)["body"] as Map)["storage"] as Map
def xml = storage["value"] as String
def cloudMatcher     = (xml =~ /confluence:([a-f0-9\-]+):workspace/)
def workspaceMatcher = (xml =~ /workspace\/([a-f0-9\-]+)/)
if (cloudMatcher.find() && workspaceMatcher.find()) {
    logger.info("Your CLOUD_ID is:     " + cloudMatcher.group(1))
    logger.info("Your WORKSPACE_ID is: " + workspaceMatcher.group(1))
} else {
    logger.warn("Could not find values — make sure the page has an Advanced Card on it")
}
```

The output will show both values:
```
Your CLOUD_ID is:     xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
Your WORKSPACE_ID is: yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy
```

---

### ACCOUNT_ID
Run this snippet in **Apps → ScriptRunner → Script Console**:

```groovy
def r = get("/wiki/rest/api/user/current").asObject(Map)
logger.info("Your ACCOUNT_ID is: " + (r.body as Map)["accountId"])
```

The output will show:
```
Your ACCOUNT_ID is: zzzzzzzzzzzzzzzzzzzzzzzz
```

---

## Running the Script

### Step 1 — Open the Script Console
1. Go to your Confluence Cloud instance
2. Click **Apps** in the top navigation bar
3. Click **ScriptRunner for Confluence**
4. Click **Script Console** in the left sidebar

You will see a large text box. This is where you paste and run the script.

### Step 2 — Paste the Script
1. Open [`scripts/lozenge-to-advanced-card.groovy`](scripts/lozenge-to-advanced-card.groovy)
2. Copy the entire file contents
3. Click inside the ScriptRunner text box
4. Press **Ctrl+A** (Windows) or **Cmd+A** (Mac) to select all
5. Press **Delete** to clear it
6. Paste the script

### Step 3 — Fill In Your Values
At the top of the script, replace the placeholders with your values:

```groovy
def PAGE_ID      = "12345678"
def BASE_URL     = "https://your-company.atlassian.net"
def CLOUD_ID     = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
def WORKSPACE_ID = "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy"
def ACCOUNT_ID   = "zzzzzzzzzzzzzzzzzzzzzzzz"
```

> ⚠️ Keep the quote marks around each value. Only replace the text inside them.

### Step 4 — Do a Dry Run First
Make sure this line near the top of the script says `true`:
```groovy
def DRY_RUN = true
```

Click **Run**. The script will log what it would do without saving any changes. The output appears in the panel below the text box:

```
Page found: 'Your Page Title' (version 3), Space: MYSPACE
Found 4 lozenge macro(s) to replace
Found page 'Home' in space MYSPACE → ID 12345678
[DRY RUN] Would replace lozenge: 'Example Card 1' → link: https://...
[DRY RUN] Would replace lozenge: 'Example Card 2' → link: https://...
─────────────────────────────────────────────
DRY RUN complete. No changes were saved.
Set DRY_RUN = false and run again to apply.
─────────────────────────────────────────────
```

Check that:
- The page title shown is the correct page ✅
- The number of lozenges found matches what you expect ✅
- Each lozenge shows a link (if you expect one) ✅

If you see `Could not find page: 'Title' — link will be empty`, it means the script could not find the internal Confluence page that lozenge was linking to. This usually means the page was not migrated or has a different title in Cloud. You will need to fix that link manually after the script runs.

### Step 5 — Apply for Real
Change `true` to `false` and click **Run** again:
```groovy
def DRY_RUN = false
```

A successful run ends with:
```
Done! Page 'Your Page Title' has been updated successfully.
```

### Step 6 — Check the Page
Open the page in Confluence. Advanced Cards should now appear in place of the broken lozenges. Click each card title to verify the links work correctly.

---

## After Running the Script

### Re-add Icons Manually
The script cannot migrate icons automatically. For each converted card:
1. Open the page in Confluence
2. Click the Advanced Card to select it
3. Click the **Edit** (pencil) icon
4. Upload the icon image for that card
5. Save

### Verify Links
- Internal links should navigate to the correct Confluence page ✅
- External links should open the correct website ✅

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `Could not fetch page. Status: 404` | PAGE_ID is wrong | Double-check the page ID from the URL |
| `Could not fetch page. Status: 403` | Insufficient permissions | Ask your Confluence admin for page edit access |
| `Found 0 lozenge macro(s) to replace` | Page already converted, or wrong page | Check you have the right PAGE_ID |
| `Could not find page: 'Title' — link will be empty` | Linked page not found in Cloud | Fix the link manually after the script runs |
| Cards don't render on the page | Mosaic app not installed | Check Mosaic is installed under Apps → Manage your apps |

---

## Running on Multiple Pages

Repeat the following for each broken page:
1. Get the `PAGE_ID` from the page URL
2. Update `PAGE_ID` at the top of the script
3. Run with `DRY_RUN = true` to preview
4. Run with `DRY_RUN = false` to apply
5. Check the page

---

## Optional — Changing Card Colours

Default colours match the DC lozenge style. Change them at the top of the script:

```groovy
def CARD_BG_COLOR   = "#F4F5F0"   // Background colour
def CARD_TEXT_COLOR = "#172B4D"   // Text colour
```

Use any hex colour code. Find hex codes at [htmlcolorcodes.com](https://htmlcolorcodes.com).

---

## Requirements

- Confluence Cloud
- ScriptRunner for Confluence Cloud
- Mosaic app (Advanced Cards | Mosaic)
- Admin access to the Confluence instance
