# The Reader View loads only a page's text and layout

The reader does not want to be tracked by default and finds today's ads distracting; in the browser they block both. They would rather pay publishers directly, but know of no system for that, so they decline tracking wherever they can. The Reader View therefore loads from an Article's Original Page only its document and its stylesheets: no scripts, ads, trackers, images or fonts. The page's main content is the same, it is there in about 1.5 s instead of 3 to 6 s, and far less is transferred.

## Consequences

- Consent and pay-or-consent walls built by a page's scripts, such as Der Postillon's, do not appear, so the Reader View does not honour them. This reverses the rule of #13 that the Reader View must not get around such a wall. A site that sends the reader to another page instead, such as Golem.de's consent page, is still shown; to let the reader decide there, that page is loaded completely.
- A page that builds its text with its own scripts yields no main content. The Reader View says so and offers to load the complete page for that Article; it does not do so on its own, so that such Feeds come to the reader's attention and can be fixed for all of them at once.
- A per-Feed list of allowed requests was considered and set aside until a Feed needs one.
