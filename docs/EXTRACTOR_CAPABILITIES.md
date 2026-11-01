# NewPipe integration — local 1.3.1 test

NewPipeExtractor v0.26.5 (GPL-3.0-or-later) sits behind PublicExtractor and the existing YouTubeProvider contract. No Composable calls it directly. Android 8 compatibility uses core-library NIO desugaring 2.1.5.

| Capability | Implementation / limits |
|---|---|
| Signed-in Home and server Home chips | Existing authenticated endpoints, account context and continuations retained |
| Guest discovery | Regional public search fallback; not personalized Home or a true Trending endpoint |
| Relevance search | NewPipe; unsupported sorting retains existing adapter |
| Playback | Verified native profiles/auth first; NewPipe fallback bounded to 7 seconds and validated by MediaPlanner; invalidated on source retry |
| Shorts | Real short-form flag from public search; initial empty results use old adapter. Not personalized Shorts recommendations |
| Playlist / Mix | NewPipe where supported, compatible initial fallback; WL/LL retain authenticated adapter |
| Channel / latest videos | Public NewPipe; account channel state and unsupported sorting retain existing endpoints |
| Public watch / related / chapters / live | StreamInfo, including HLS if available; authenticated watch and queue context retain existing adapter |
| Subscriptions / history / likes / comments writes / notifications | Existing authenticated adapter |
| Public comments | Read-only NewPipe data, no invented action endpoints; signed-in watch retains actionable account data |

Public transport receives no account cookies. Localization/country are per extractor. Stream cache: 24 entries, 90 seconds, singleflight locks shared by watch/playback. Continuations: opaque registry of 128 entries/20 minutes; query, locale and type ownership validated. Tokens never cross providers. Cancellation cancels transport, requests/operations have deadlines, no infinite retries. Initial failure permits one compatible fallback. Existing repository caches preserve account separation and scroll state.

Live smoke test was attempted but the cloud proxy rejected HTTPS CONNECT. This is not an online pass. Real-device source testing remains necessary.

## Distribution

Full GPL text is bundled in the license screen. Distributing the combined APK requires a compliant corresponding-source/licensing arrangement. No application source was committed or pushed. APK and source snapshot are prepared locally; public binary delivery must respect both the license obligation and the user's no-source-push instruction. No proprietary APK assets were imported.
