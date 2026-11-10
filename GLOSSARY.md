# FreshReader

An Android client for reading the articles aggregated by a single self-hosted FreshRSS instance, built for one reader's daily routine.

## Language

**Server**:
The reader's own FreshRSS instance; it subscribes to feeds, fetches them and holds all articles and their read state. FreshReader is only a client to it.
_Avoid_: Backend, instance, account

**Account**:
The reader's user on a Server, identified by the Server's URL and the username; the read marks not yet sent belong to it.
_Avoid_: Login, profile, user

**Feed**:
A subscribed source of articles, as configured on the Server.
_Avoid_: Subscription, source, channel

**Article**:
A single entry published by a Feed.
_Avoid_: Item, entry, post

**Category**:
A named group of Feeds, as defined on the Server; every Feed belongs to exactly one Category.
_Avoid_: Group, folder, label, tag

**Reading Order**:
The reader's chosen sequence of Categories, in which their Articles are presented; Categories not in it follow alphabetically.
_Avoid_: Sort order, priority, position

**Category Colour**:
A muted colour from a fixed palette that marks a Category's header in the list, so that the reader sees which Category they are in; chosen by the reader per Category, or else picked automatically to differ from its neighbours'.
_Avoid_: Category tint, label colour, theme

**Feed Settings**:
The reader's per-Feed preferences, kept on the device rather than on the Server.
_Avoid_: Feed config, feed options

**View Mode**:
The Feed Setting that decides how an Article is shown: as Feed Content, as Reader View, or as Original Page.
_Avoid_: Display mode, layout

**Feed Content**:
The Article content exactly as the Server delivers it.
_Avoid_: RSS full text, summary, server content

**Reader View**:
The Article's original web page, reduced to its main content without ads, navigation or banners.
_Avoid_: Minified web view, readability mode, full text

**Original Page**:
The Article's web page on its own site, shown as-is.
_Avoid_: Website, link, full web view

**Bonus Text**:
Text a Feed hides inside an Article that a reader would only see by hovering or clicking, such as an image's title text or a mail link's subject; shown only for Feeds whose Feed Settings ask for it.
_Avoid_: Alt text, title text, hover text, caption
