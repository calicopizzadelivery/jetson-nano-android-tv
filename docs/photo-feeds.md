# Photo feeds for an ambient screensaver

Research for a Chromecast-style clock-over-photos dream. Every endpoint below
was called on 2026-09-26 and the results are what came back, not what the
documentation claims.

## Recommendation: Wikimedia Commons

No API key, an explicit licence on every image, attribution metadata in the
same response, and arbitrary render widths. For an appliance that ships to
other people, the licence clarity matters more than the photography being
marginally better elsewhere.

Curated pools, queried by category:

| Category | Files (first page) |
| --- | --- |
| `Featured_pictures_of_landscapes` | 500+ (paginated) |
| `Quality_images_of_landscapes` | 500+ (paginated) |
| `Featured_pictures_of_plants` | 74 |

One call gets image URL, a 1920px render and the licence in one go:

    https://commons.wikimedia.org/w/api.php
      ?action=query
      &generator=categorymembers
      &gcmtitle=Category:Featured_pictures_of_landscapes
      &gcmtype=file&gcmlimit=50
      &prop=imageinfo&iiprop=url|extmetadata
      &iiurlwidth=1920&format=json

`iiurlwidth=1920` returns a `thumburl` at that width — a 9480x6320 original
came back as an 810 KB JPEG, which is the right size to hand a TV.
`extmetadata` carries `LicenseShortName`, `Artist` and `Credit`, so the credit
line can be generated rather than hand-maintained.

There is also a daily single-image feed, useful for a "picture of the day"
mode: `https://api.wikimedia.org/feed/v1/wikipedia/en/featured/YYYY/MM/DD`
returns an `image` object with full metadata including `license.type` and
`license.url`.

Send a real `User-Agent`; Wikimedia blocks generic ones.

## Second choice: NASA

`https://images-api.nasa.gov/search?q=earth+landscape&media_type=image` — no
key, 100 results per query. NASA media is generally public domain, so there is
no attribution obligation at all, which makes it the simplest option legally.
Earth-from-orbit and astronomy imagery suits a television.

## Avoid for a shipped device

Unsplash, Pexels and Pixabay all need an API key, which means one quota shared
across every box in the field rather than per-device. Unsplash's API terms
(checked 2026-09-26) are a poor fit specifically:

- **Hotlinking is mandatory** — section 6 requires embedding their URLs rather
  than caching locally, so the screensaver breaks whenever the network does.
- **Attribution must link back** to the photographer's Unsplash profile, which
  a TV screensaver cannot meaningfully offer.
- **Quota and termination** — they may set quotas and may "suspend or
  terminate access ... at any time and without notice".

None of that is unreasonable for a web app; it is wrong for an appliance.

## Two honest caveats

**"Curated" means technically reviewed, not content-filtered.** Commons
Featured and Quality pictures are human-reviewed for photographic quality, not
screened for a family living room. Restricting to landscape, plant and
astronomy categories is the practical safety filter; the broader Featured
pool contains anatomical and historical images that nobody wants appearing
behind a clock.

**Cache locally.** CC licences permit redistribution with attribution, so
unlike Unsplash the images can be downloaded and kept. The screensaver should
hold a local set and refresh opportunistically, so a box with no network still
has something to show.
