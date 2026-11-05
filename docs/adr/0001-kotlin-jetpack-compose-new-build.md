# Native Android app in Kotlin + Jetpack Compose, built from scratch

FreshReader is a new Android app written in Kotlin with Jetpack Compose, even though the author is most fluent in C#. The app is small (one Article list, one Article view, an in-app browser, a handful of Google Reader API calls against the Server), so switching language is cheap, while Kotlin/Compose is the Android default with the best official documentation and keeps F-Droid publication possible.

## Considered Options

- **Fork Fluent Reader Lite (Flutter/Dart, BSD-3)**: rejected because the wanted changes (Category-grouped navigation, automatic read marking, ascending sort, reader view, Title Text) hit the core of its UI; mainly the small API client would survive. This was reasoned, not verified against its code.
- **C# with .NET MAUI or Avalonia**: rejected because F-Droid currently cannot build .NET apps (no dotnet app in F-Droid; MAUI not buildable with distro SDKs, per F-Droid forum and dotnet/core#10103, i.e. weak sources), and its Android ecosystem is much smaller.
