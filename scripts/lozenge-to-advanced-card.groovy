import groovy.json.JsonOutput

// ─────────────────────────────────────────────────────────────────────────────
// CONFIGURATION — Fill in these 5 values before running
// ─────────────────────────────────────────────────────────────────────────────
def PAGE_ID      = "YOUR_PAGE_ID_HERE"
def BASE_URL     = "https://YOUR_INSTANCE.atlassian.net"
def CLOUD_ID     = "YOUR_CLOUD_ID_HERE"
def WORKSPACE_ID = "YOUR_WORKSPACE_ID_HERE"
def ACCOUNT_ID   = "YOUR_ACCOUNT_ID_HERE"
def CARD_BG_COLOR   = "#F4F5F0"   // Card background colour — adjust to match your branding
def CARD_TEXT_COLOR = "#172B4D"   // Card text colour

// Set to true to preview changes without saving anything to Confluence
// Set to false when you are ready to apply the changes for real
def DRY_RUN = true
// ─────────────────────────────────────────────────────────────────────────────

logger.info("Fetching page with ID: ${PAGE_ID}")

def getResponse = get("/wiki/api/v2/pages/${PAGE_ID}")
    .header("Content-Type", "application/json")
    .queryString("body-format", "storage")
    .asObject(Map)

assert getResponse.status == 200 : "Could not fetch page. Status: ${getResponse.status}"

def pageData       = getResponse.body as Map
def versionMap     = pageData["version"] as Map
def currentVersion = versionMap["number"] as Integer
def pageTitle      = pageData["title"] as String
def bodyMap        = pageData["body"] as Map
def storageMap     = bodyMap["storage"] as Map
def storageContent = storageMap["value"] as String
def spaceId        = pageData["spaceId"] as String

// Look up the space key — needed for scoped page title searches
def spaceKey = ""
def spaceResponse = get("/wiki/api/v2/spaces/${spaceId}")
    .header("Content-Type", "application/json")
    .asObject(Map)
if (spaceResponse.status == 200) {
    def spaceData = spaceResponse.body as Map
    spaceKey = spaceData["key"] as String
}

logger.info("Page found: '${pageTitle}' (version ${currentVersion}), Space: ${spaceKey}")

def lozengePattern = /(?s)<ac:structured-macro ac:name="lozenge".*?<\/ac:structured-macro>/
def lozengeMacros  = storageContent.findAll(lozengePattern)

logger.info("Found ${lozengeMacros.size()} lozenge macro(s) to replace")

if (lozengeMacros.isEmpty()) {
    logger.info("No lozenge macros found. Nothing to do!")
    return
}

def updatedContent = storageContent

lozengeMacros.each { String lozengeMacro ->
    String title       = extractParameter(lozengeMacro, "title", "Untitled")
    String linkContent = extractParameter(lozengeMacro, "link",  "")
    String bodyContent = extractBodyContent(lozengeMacro)
    String plainBody   = bodyContent.replaceAll(/<[^>]+>/, "").trim()
    String cleanTitle  = unescapeXml(title)
    String cleanBody   = unescapeXml(plainBody)
    String linkUrl     = resolveLinkUrl(linkContent, BASE_URL, spaceKey)

    String advancedCard = buildAdvancedCardMacro(
        cleanTitle, cleanBody, linkUrl,
        PAGE_ID, spaceKey, spaceId,
        CLOUD_ID, WORKSPACE_ID, ACCOUNT_ID,
        CARD_BG_COLOR, CARD_TEXT_COLOR
    )

    if (!DRY_RUN) {
        updatedContent = updatedContent.replace(lozengeMacro, advancedCard)
    }
    logger.info("${DRY_RUN ? '[DRY RUN] Would replace' : 'Replaced'} lozenge: '${cleanTitle}' → link: ${linkUrl ?: '(empty)'}")
}

// Stop here if this is a dry run — nothing has been saved
if (DRY_RUN) {
    logger.info("─────────────────────────────────────────────")
    logger.info("DRY RUN complete. No changes were saved.")
    logger.info("Set DRY_RUN = false and run again to apply.")
    logger.info("─────────────────────────────────────────────")
    return
}

def updatePayload = [
    id     : PAGE_ID,
    status : "current",
    title  : pageTitle,
    body   : [representation: "storage", value: updatedContent],
    version: [number: currentVersion + 1]
]

def putResponse = put("/wiki/api/v2/pages/${PAGE_ID}")
    .header("Content-Type", "application/json")
    .body(JsonOutput.toJson(updatePayload))
    .asObject(Map)

assert putResponse.status == 200 : "Could not update page. Status: ${putResponse.status}"
logger.info("Done! Page '${pageTitle}' has been updated successfully.")

// ─────────────────────────────────────────────────────────────────────────────
// HELPER FUNCTIONS
// ─────────────────────────────────────────────────────────────────────────────

/** Extracts a named ac:parameter value from a macro block. */
String extractParameter(String macroBlock, String paramName, String defaultValue) {
    def matcher = (macroBlock =~ /(?s)<ac:parameter ac:name="${paramName}">(.*?)<\/ac:parameter>/)
    return matcher.find() ? matcher.group(1).trim() : defaultValue
}

/** Extracts the rich-text body content from a macro block. */
String extractBodyContent(String macroBlock) {
    def matcher = (macroBlock =~ /(?s)<ac:rich-text-body>(.*?)<\/ac:rich-text-body>/)
    return matcher.find() ? matcher.group(1).trim() : ""
}

/** Converts XML escape sequences back to plain characters. */
String unescapeXml(String text) {
    return text
        .replace("&amp;",  "&")
        .replace("&lt;",   "<")
        .replace("&gt;",   ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}

/**
 * Resolves a lozenge link parameter to a plain URL string.
 * External links are returned as-is.
 * Internal page links are looked up via the Confluence API.
 */
String resolveLinkUrl(String linkContent, String baseUrl, String spaceKey) {
    if (!linkContent) return ""

    // External URL — extract href directly
    if (linkContent.contains("<a href=")) {
        def matcher = (linkContent =~ /href="(.*?)"/)
        if (matcher.find()) return unescapeXml(matcher.group(1))
    }

    // Internal page link — look up by title
    if (linkContent.contains("ri:page")) {
        def titleMatcher = (linkContent =~ /ri:content-title="(.*?)"/)
        if (titleMatcher.find()) {
            String linkedPageTitle = unescapeXml(titleMatcher.group(1))
            return lookupPageUrl(linkedPageTitle, baseUrl, spaceKey)
        }
    }

    return ""
}

/**
 * Looks up a Confluence page URL by title.
 * Searches the current space first, then falls back to the whole instance.
 */
String lookupPageUrl(String linkedPageTitle, String baseUrl, String spaceKey) {
    // Step 1: search within the same space
    if (spaceKey) {
        def r = get("/wiki/api/v2/pages")
            .header("Content-Type", "application/json")
            .queryString("title",     linkedPageTitle)
            .queryString("space-key", spaceKey)
            .queryString("limit",     "1")
            .asObject(Map)
        if (r.status == 200) {
            def pages = (r.body as Map)["results"] as List
            if (pages && !pages.isEmpty()) {
                String foundId = (pages[0] as Map)["id"] as String
                logger.info("Found page '${linkedPageTitle}' in space ${spaceKey} → ID ${foundId}")
                return "${baseUrl}/wiki/pages/viewpage.action?pageId=${foundId}"
            }
        }
    }

    // Step 2: fallback — search across the whole instance
    def r2 = get("/wiki/api/v2/pages")
        .header("Content-Type", "application/json")
        .queryString("title", linkedPageTitle)
        .queryString("limit", "1")
        .asObject(Map)
    if (r2.status == 200) {
        def pages = (r2.body as Map)["results"] as List
        if (pages && !pages.isEmpty()) {
            String foundId = (pages[0] as Map)["id"] as String
            logger.info("Found page '${linkedPageTitle}' (instance-wide) → ID ${foundId}")
            return "${baseUrl}/wiki/pages/viewpage.action?pageId=${foundId}"
        }
    }

    logger.warn("Could not find page: '${linkedPageTitle}' — link will be empty")
    return ""
}

/** Builds the full Advanced Card (Mosaic) macro XML for one card. */
String buildAdvancedCardMacro(
    String title, String body, String linkUrl,
    String pageId, String spaceKey, String spaceId,
    String cloudId, String workspaceId, String accountId,
    String bgColor, String textColor
) {
    String localId      = UUID.randomUUID().toString()
    String macroId      = UUID.randomUUID().toString().replace("-", "").substring(0, 11)
    String cardId       = UUID.randomUUID().toString().replace("-", "").substring(0, 10)
    String extensionKey = "fdc883c7-e768-4224-989e-c91b96eaefac/3ed6b50f-538a-49b1-99a7-0433758336a5/static/cfm-cards"
    String extensionId  = "ari:cloud:ecosystem::extension/${extensionKey}"
    String contextId    = "ari:cloud:confluence:${cloudId}:workspace/${workspaceId}"

    def cardData = [
        id                    : cardId,
        color                 : bgColor,
        textColor             : textColor,
        title                 : title,
        body                  : body,
        backgroundColorPalette: "Default",
        textColorPalette      : "Default",
        destination           : "externalUrl",
        filterSpace           : "false",
        externalUrl           : linkUrl
    ]

    String cardsJson    = JsonOutput.toJson([cardData])
    String cardsJsonXml = cardsJson.replace("&", "&amp;")

    String nodeXml = buildNodeXml(
        localId, extensionKey, extensionId,
        pageId, spaceKey, spaceId,
        contextId, accountId, cloudId,
        cardsJsonXml, macroId
    )

    return "<ac:adf-extension>${nodeXml}<ac:adf-fallback>${nodeXml}</ac:adf-fallback></ac:adf-extension>"
}

/** Builds the raw ADF node XML for the Advanced Card extension. */
String buildNodeXml(
    String localId, String extensionKey, String extensionId,
    String pageId, String spaceKey, String spaceId,
    String contextId, String accountId, String cloudId,
    String cardsJsonXml, String macroId
) {
    return "<ac:adf-node type=\"extension\">" +
        "<ac:adf-attribute key=\"extension-key\">${extensionKey}</ac:adf-attribute>" +
        "<ac:adf-attribute key=\"extension-type\">com.atlassian.ecosystem</ac:adf-attribute>" +
        "<ac:adf-attribute key=\"parameters\">" +
            "<ac:adf-parameter key=\"local-id\">${localId}</ac:adf-parameter>" +
            "<ac:adf-parameter key=\"extension-id\">${extensionId}</ac:adf-parameter>" +
            "<ac:adf-parameter key=\"extension-title\">Advanced Cards | Mosaic</ac:adf-parameter>" +
            "<ac:adf-parameter key=\"layout\">extension</ac:adf-parameter>" +
            "<ac:adf-parameter key=\"forge-environment\">PRODUCTION</ac:adf-parameter>" +
            "<ac:adf-parameter key=\"embedded-macro-context\">" +
                "<ac:adf-parameter key=\"extension-data\">" +
                    "<ac:adf-parameter key=\"type\">macro</ac:adf-parameter>" +
                    "<ac:adf-parameter key=\"content\">" +
                        "<ac:adf-parameter key=\"id\">${pageId}</ac:adf-parameter>" +
                        "<ac:adf-parameter key=\"type\">page</ac:adf-parameter>" +
                    "</ac:adf-parameter>" +
                    "<ac:adf-parameter key=\"space\">" +
                        "<ac:adf-parameter key=\"key\">${spaceKey}</ac:adf-parameter>" +
                        "<ac:adf-parameter key=\"id\">${spaceId}</ac:adf-parameter>" +
                    "</ac:adf-parameter>" +
                "</ac:adf-parameter>" +
            "</ac:adf-parameter>" +
            "<ac:adf-parameter key=\"context-ids\">" +
                "<ac:adf-parameter key=\"context-ids-value\">${contextId}</ac:adf-parameter>" +
            "</ac:adf-parameter>" +
            "<ac:adf-parameter key=\"account-id\">${accountId}</ac:adf-parameter>" +
            "<ac:adf-parameter key=\"cloud-id\">${cloudId}</ac:adf-parameter>" +
            "<ac:adf-parameter key=\"guest-params\">" +
                "<ac:adf-parameter key=\"image-position\">left</ac:adf-parameter>" +
                "<ac:adf-parameter key=\"text-alignment\">left</ac:adf-parameter>" +
                "<ac:adf-parameter key=\"number-of-columns\">1</ac:adf-parameter>" +
                "<ac:adf-parameter key=\"cards\">${cardsJsonXml}</ac:adf-parameter>" +
                "<ac:adf-parameter key=\"preview-theme\">light</ac:adf-parameter>" +
                "<ac:adf-parameter key=\"id\">${macroId}</ac:adf-parameter>" +
            "</ac:adf-parameter>" +
        "</ac:adf-attribute>" +
        "<ac:adf-attribute key=\"text\">Advanced Cards | Mosaic</ac:adf-attribute>" +
        "<ac:adf-attribute key=\"layout\">default</ac:adf-attribute>" +
        "<ac:adf-attribute key=\"local-id\">${localId}</ac:adf-attribute>" +
        "</ac:adf-node>"
}
