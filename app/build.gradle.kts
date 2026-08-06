plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rfsat.sts"
    compileSdk = 36

    defaultConfig {
        // Play listing identity, and PERMANENT once published — an app's
        // applicationId cannot be changed afterwards without shipping a
        // different app and losing every install and review. The Kotlin and
        // resource namespace stays com.rfsat.sts, so no source moves.
        //
        // Play requires at least two segments separated by a dot, letters,
        // digits and underscores only, and a letter at the start of every
        // segment. "com.STS" satisfies all of that; uppercase is unusual but
        // legal, and matches the com.VTBC convention already in use.
        applicationId = "com.STS"
        minSdk = 26
        targetSdk = 36
        // VERSIONING RULE for this project — follow it on every release:
        //
        //   <brand>.<major>.<minor>
        //     brand  1 = STS. Does not change.
        //     major  incremented when a FEATURE is added; minor resets to 0.
        //     minor  incremented when a CORRECTION is made.
        //
        //   versionCode increments on EVERY build that leaves this machine,
        //   without exception. Play rejects a bundle whose code is not
        //   strictly greater than the last uploaded one, and a code reused
        //   during testing is impossible to tell apart afterwards.
        //
        // 1.52.1 — correction: the stream arrived and every frame was blank.
        //
        //   The log from the field showed the stream open, frames at
        //   1080 x 1101 at the right rate, and for every one of them
        //   "contrast between mark and paper is only 0" and "only 0 usable
        //   edge points". A picture of nothing, delivered punctually.
        //
        //   TextureView.getBitmap() copies out of the view's own GL surface.
        //   Off the thread that owns that surface the copy comes back BLANK
        //   rather than failing — no exception, no null, a bitmap of the
        //   right size full of one value. The grabber was a background
        //   thread, so every frame it read was empty and the detector
        //   measured it confidently.
        //
        //   The read-back is now posted to the main thread and only the read
        //   is: the luma conversion and the detection stay on a worker, since
        //   doing those on the UI thread would cost the viewfinder its frame
        //   rate. A frame with no variation in it at all is also now
        //   discarded rather than measured, because a confident measurement
        //   of nothing is the failure this whole class of bug produces.
        //
        //   TWO MORE THINGS THE SAME LOG SHOWED. The stream was opened TWICE,
        //   four milliseconds apart — selecting the source, restoring the
        //   saved address and resuming the screen can all fire at once — which
        //   puts two decoders on one SurfaceTexture and leaks the first. And
        //   the phone camera was bound one millisecond BEFORE the stream
        //   opened and stayed bound behind it, costing power and holding the
        //   camera against every other app for frames that went nowhere.
        //   Both fixed; the camera is now started only when it is the source.
        //
        // 1.52.0 — correction: a stream source was never opened at all.
        //
        //   Reported with a log, and the log was the evidence: two lines
        //   about the phone camera being bound, ring fits from the camera,
        //   and NOT ONE line about the stream. An address that plays in VLC
        //   was never being handed to the decoder.
        //
        //   Starting an external source was done in exactly one place — the
        //   live-detection button — and that button refuses until a reference
        //   frame has been captured. A reference frame comes from the source.
        //   So a stream source sat in a deadlock the app could not report:
        //   no frames until detection starts, no detection until a frame
        //   arrives, and nothing in the log either way, because the code that
        //   would have logged it never ran.
        //
        //   The decoder work in 1.50.0 was necessary and was never reached.
        //   That is worth stating plainly: two releases were spent on the
        //   right fix to the wrong stage, because the failure produced no
        //   evidence and the absence of evidence was not read as evidence.
        //
        //   A stream is now opened whenever the shooter has said what they
        //   want: on choosing the source, on Done in the address box, on the
        //   new Connect button, on returning to the screen, and on start-up
        //   when a saved address is restored. The live button, when there is
        //   no reference, now says whether the actual cause is that nothing
        //   is connected.
        //
        //   Every stage is logged — the attempt, the first frame with its
        //   size, and every error — because a failure that leaves no trace
        //   costs a release to find.
        //
        // 1.51.0 — feature: the stream address is remembered.
        //
        //   Asked for, and obviously right once asked: a stream address is
        //   long, exact and typed on a phone keyboard —
        //   rtsp://192.168.1.254:554/live — and it was discarded every time
        //   the screen closed. Every session began by typing it again from
        //   memory, at the firing point, which is the worst place to be
        //   doing it.
        //
        //   Kept in ordinary preferences, so it survives an app upgrade as
        //   well as a restart, and it is backed up: unlike an API key there
        //   is nothing secret about it and nothing that stops it being read
        //   on another phone.
        //
        //   Saved when the source is STARTED, and again on leaving the screen
        //   if what is in the box is a complete address — typing one and
        //   walking to the firing point without pressing Start is ordinary,
        //   and losing it for that would be the same fault in a smaller
        //   costume. Anything that is not yet a URL is left alone, so a
        //   half-typed address cannot replace a working one.
        //
        //   The source choice is remembered with it, but only when there is
        //   an address to go back to: restoring "RTSP stream" with an empty
        //   box is restoring a screen that cannot start.
        //
        // 1.50.0 — feature: RTSP is decoded by ExoPlayer, so a stream that
        //          plays in VLC now plays here.
        //
        //   Reported: an RTSP address entered on the Session tab showed
        //   nothing, and the same address plays in VLC. Three faults, and any
        //   one of them was enough.
        //
        //   1. THE DECODER. It was the platform MediaPlayer, whose RTSP
        //      support is RTP over UDP and nothing else. It cannot do RTSP
        //      interleaved over TCP — which is exactly what VLC falls back to,
        //      and what many cameras offer by preference or exclusively — and
        //      it does not decode H.265 on that path at all. So "works in
        //      VLC, not here" was the expected outcome, not a surprise.
        //
        //      ExoPlayer's RTSP source does both transports and uses the
        //      device's own decoders. UDP is tried first because it is lower
        //      latency; on failure TCP is tried automatically, because
        //      leaving the shooter to guess which of the two their camera
        //      wants is not a diagnosis.
        //
        //   2. THE SURFACE. RTSP decodes into the TextureView the app then
        //      reads frames back out of, and a TextureView made visible a
        //      moment ago has NO SurfaceTexture until the next layout pass.
        //      Reading it straight out of the button handler returned null
        //      whenever the address was typed and Start pressed without a
        //      pause. It now waits for the surface and says so.
        //
        //   3. THE SILENCE. A stream that connected and delivered no picture
        //      looked identical to an app that had ignored the address. Every
        //      failure now carries a sentence that can be acted on — no
        //      answer, refused as unauthorised, cannot be decoded — and a
        //      watchdog speaks up after ten seconds without a frame.
        //
        //   Also: the source picker refuses an rtsp:// address under MJPEG
        //   and an http:// one under RTSP rather than failing obscurely, and
        //   says "scope or action camera" since that is what people connect.
        //
        // 1.49.1 — documentation: the changelog reads to one standard from
        //          end to end, and the pre-publication checklist is gone.
        //
        //   Reported: far more detail for releases up to 1.9.0 than for the
        //   forty since. True, and it was worse than uneven — the old entries
        //   were full prose carrying measured results, the new ones were a
        //   single line each, so the file changed character half way down and
        //   the recent work looked slighter than it was.
        //
        //   All 71 releases now get the same treatment: a summary sentence,
        //   then what was wrong or what was added and the figure that settled
        //   it where there is one. Roughly 4,800 words, against 4,900 for the
        //   eighteen oldest releases alone before this. Everything checked
        //   mechanically — no duplicate versions, strictly descending, and
        //   every version in this file's changelog present in the README's.
        //
        //   The full entry stays here, which the README now says plainly.
        //   This is where it is written while the work is being done; a
        //   summary reconstructed afterwards is a summary of what was
        //   remembered.
        //
        //   "Before release" is deleted, as asked. Two of its four items were
        //   already dead — the keystore and secrets are configured and the
        //   listing is written — and the other two are ongoing work rather
        //   than gates before publishing.
        //
        // 1.49.0 — feature: the picture can be stretched back to square, the
        //          two registration routes agree, and Home stops showing the
        //          rule book's distance instead of yours.
        //
        //   THE DISTANCE. 200 m typed on the Session tab, 10 m on Home. Both
        //   numbers were real: Home was showing the distance printed in the
        //   ISSF rule set while the session was scored at the one entered.
        //   The correction, the MOA figures and the group statistics all use
        //   the session's number, so the summary screen was contradicting
        //   every screen that matters. Worse, starting a new string reset the
        //   distance to the rule book's, silently throwing away what had been
        //   typed for the previous one — a competition face used at another
        //   distance in training is ordinary, and the rule's figure is a
        //   DEFAULT, not a correction. Home now shows the session's distance
        //   and, where it differs, the rule's in brackets.
        //
        //   THE PICTURE, NOT THE CIRCLES. Reported: the estimated circles sit
        //   over the printing on one axis and not the other, most often
        //   vertically. The fix could have been to reshape the drawn circles,
        //   and that would have been the wrong one — it leaves an ELLIPTICAL
        //   geometry in the pipeline, and the scale, the gauge, the ring
        //   radii and every hole-size gate are written in terms of ONE
        //   millimetres-per-pixel number. An ellipse gives them two, and each
        //   stage then has to know which applies in which direction.
        //
        //   So the picture is stretched instead, and the rings are round
        //   before any of that runs. The app measures the stretch from the
        //   fitted ring family, fills the two percentages in, and waits: a
        //   good photograph of a flat card measures one to two per cent out
        //   of round on segmentation noise alone, and applying that would
        //   distort a picture that was already right. Same rule as the tilt
        //   estimate, for the same reason.
        //
        //   It refuses the case it cannot express. A card photographed from
        //   one side is foreshortened along an axis that is not the picture's
        //   own; stretching would make that worse while looking like a fix.
        //   A suggestion is offered only when the long axis lies within 20
        //   degrees of the width or the height.
        //
        //   Applying re-registers from the new pixels and throws away the
        //   box, the ring fit, the mark radius and the registration itself —
        //   all of them measured in a picture that no longer exists. The
        //   ORIGINAL is kept and every stretch is applied to it, so adjusting
        //   a percentage up and down does not resample the card repeatedly.
        //
        //   THE TWO ROUTES DISAGREED. "Identify target and register" and
        //   "Auto-detect the target" returned different bounding boxes for
        //   the same photograph, and only the first drew the rings it had
        //   measured. The second was fitting no rings at all — one circle
        //   round the aiming mark, a box derived from that, and nothing on
        //   screen to say whether the scoring geometry lined up with the
        //   printing.
        //
        //   Auto-detect now fits the rings too, seeded from the mark it has
        //   just found, draws them, and takes its box from the same shared
        //   code the other route uses. What it still does NOT do is identify
        //   the face, and it should not: the shooter selected one, that is
        //   the whole difference between the two buttons, and it now says so
        //   in as many words.
        //
        // 1.48.3 — correction: the guide is edited IN the author's own
        //          document now. The generator that kept destroying its
        //          formatting is gone.
        //
        //   Reported plainly: do not change the formatting, do not add page
        //   breaks, do not remove the RFSAT logo from the first page, do not
        //   change the heading styles.
        //
        //   All four had the same cause. The guide was REBUILT FROM SCRATCH
        //   by a script that emitted every paragraph, table and page break
        //   itself. That works exactly once. The document then goes to the
        //   author, who lays it out in Word — a real template, real heading
        //   styles, the logo on the title page, page breaks where a reader
        //   wants them — and the next regeneration hands back the script's
        //   own idea of a document instead. Twice.
        //
        //   A generator cannot preserve what it was never told about, and it
        //   cannot be taught a Word template. So the document is the source
        //   now. docs/guide/edit_guide.py opens it, substitutes the strings
        //   it is given, and saves: styles, images, headers, footers,
        //   numbering, section properties and page breaks are never written,
        //   so they cannot be lost. Verified — the output differs from the
        //   author's copy in the version string and in nothing else, image
        //   and style parts included.
        //
        //   It refuses to save when an edit does not match exactly once. An
        //   edit that silently matches nothing is how a guide comes to
        //   describe a setting renamed three releases ago.
        //
        //   Structural changes are made in Word and come back as the new
        //   baseline. No script writes paragraphs into this document again.
        //
        // 1.48.2 — documentation: the shooter's own edits are now the
        //          baseline the guide is built from, and the generator that
        //          would have overwritten them lives in the repository.
        //
        //   The guide came back proof-read: a link written out in full, a
        //   parenthesis removed, several articles dropped. Six edits. The
        //   generator that produces the document knew nothing about any of
        //   them, so the next reissue would have silently reverted every one
        //   — the author's wording replaced by mine, with nothing to show it
        //   had happened.
        //
        //   All six are now in the generator's own text, verified by
        //   regenerating and diffing: the only remaining difference from the
        //   returned document is the version on the title page.
        //
        //   The generator itself was living in a temporary directory, which
        //   is how it came to be possible to lose the edits in the first
        //   place. It is now docs/guide/, with the returned document kept
        //   beside it as baseline_v<version>.docx and the rule written down:
        //   diff the returned copy by TEXT first, fold every difference in,
        //   regenerate, diff again.
        //
        // 1.48.1 — documentation: the guide has a permanent address, and
        //          the README's changelog is no longer thirty-nine releases
        //          out of date.
        //
        //   The README stopped at 1.9.1. Anyone reading the repository was
        //   told what the app did in October and nothing since — including
        //   about the AI scoring, the punched-card corpus and every accuracy
        //   result measured on it. It now carries one line per release back
        //   to 1.10.0, newest first, and says plainly that the full entry
        //   lives in this file, which is where it is written as the work is
        //   done rather than reconstructed from memory afterwards.
        //
        //   The guide is published at
        //   www.rfsat.com/download/STS-User-Guide.pdf and linked from the
        //   top of the README. The PDF filename carries NO version so the
        //   address survives every reissue; the version is on the title page,
        //   which is where a reader can see it anyway.
        //
        // 1.48.0 — feature: the photograph now survives a restart, and
        //          three things on Results that read badly were fixed.
        //
        //   THE SCREEN CONTRADICTED ITSELF. The box said "No adjustment — the
        //   sight is already centred" and the line directly beneath it said
        //   "Move the point of impact 1.4 mm up and 0.3 mm right". Both were
        //   computed correctly and both described the same state — a residual
        //   smaller than one click — but two places were deciding the same
        //   question independently, and only one of them had noticed there
        //   was nothing to do. SightCorrection.needsAdjustment is now that
        //   answer, decided once, from the same quantities the instruction is
        //   built from and NOT by reading the instruction back. The detail
        //   line says what the residual is and why it is being left alone.
        //
        //   THE PHOTOGRAPH WAS THROWN AWAY ON EXIT. The session survives a
        //   restart, so the shooter came back to their score with "My photo"
        //   greyed out — and that view is the only one in which a MISSED shot
        //   is visible: on the template an undetected hole leaves nothing
        //   behind to notice. The old comment defended this, saying the loss
        //   cost the background of a plot and no score. It was answering the
        //   wrong question. The rectified card is now kept as a JPEG in the
        //   app's own files, capped at 2048 px on its longest side, written
        //   off the main thread, and reloaded on demand. It is deleted when
        //   the session it belongs to ends, because the alternative is the
        //   previous card sitting under the next session's shots.
        //
        //   NUMBERS THAT DID NOT LINE UP. The angular figures were four
        //   numbers inside two sentences padded with spaces, in a
        //   proportional font, which aligns nothing. They are now a
        //   right-aligned monospaced table: elevation and windage down the
        //   side, MRAD and MOA across.
        //
        //   BULLET LISTS WITH NO HANGING INDENT. Every list was a plain
        //   string starting "• ", and a plain string does not know a bullet
        //   is there: the second line of any item that wrapped ran back to
        //   the left margin, under the dot, so four warnings read as eight
        //   items of which four began with a dot. A hanging indent cannot be
        //   done with spaces — they do not survive word wrap — so it is a
        //   paragraph span, sized from the text so it stays right when the
        //   shooter changes the font scale.
        //
        // 1.47.1 — correction: check box labels ignored the theme.
        //
        //   Exactly the trap the borderless buttons fell into, one widget
        //   along: a CompoundButton takes its label colour from the PLATFORM
        //   theme's text appearance, not from the text colours this app sets
        //   on its own theme. So under Dark and both night modes every check
        //   box label was drawn in the framework's default grey while the
        //   one-line note directly beneath it followed the theme — two
        //   colours, one paragraph.
        //
        //   Stated now on a style applied to the view, which is the one place
        //   the platform cannot override, and applied to all 16 of them. The
        //   box itself is tinted with the theme accent for the same reason:
        //   the framework's default blue tick had no business on a
        //   monochrome red screen.
        //
        // 1.47.0 — feature: which AI service is used is now three separate,
        //          stated choices, and the app no longer names one it is not
        //          calling.
        //
        //   There was ONE service setting. It decided both what scored a card
        //   on import and what the second opinion asked — and every message
        //   in the app was written when there was only Claude, so picking
        //   OpenAI changed the request but not a word on screen: "Asking
        //   Claude…", "Scored by Claude", "Claude sees 7 shots". The app was
        //   in fact calling the service that was picked. It just said the
        //   other one's name throughout, which is indistinguishable from
        //   ignoring the setting and was reported as exactly that.
        //
        //   Wording that names a component it is not using is not cosmetic.
        //   It is the app lying about what it did, and the shooter has no way
        //   to tell the lie from the bug.
        //
        //   Settings now asks three questions, each saying what it governs:
        //
        //     On import, score with:  Embedded / Claude / OpenAI
        //     Second opinion asks:    Claude / OpenAI
        //     Service to set up:      Claude / OpenAI  (key and model only)
        //
        //   The first two are INDEPENDENT on purpose: asking the other
        //   service is what makes a second opinion worth having, and one
        //   shared setting made that impossible to express.
        //
        //   Every message now carries the label of the service about to be
        //   called, or none at all. An existing installation keeps the
        //   service it was already using for both.
        //
        //   The import picker also shows what was CHOSEN rather than what
        //   will run: naming a service with no key still falls back to the
        //   embedded algorithms, but silently springing the picker back to
        //   Embedded looked like the choice had been thrown away.
        //
        // 1.46.0 — feature: API keys survive an upgrade, and cannot be
        //          silently lost for ever.
        //
        //   An ordinary in-place upgrade never touched them. What did was a
        //   RESTORE ONTO ANOTHER PHONE. The keys are encrypted with a master
        //   key in the Android Keystore, and the Keystore does not travel
        //   with a backup — so the restored file was ciphertext nobody could
        //   read, EncryptedSharedPreferences refused to open it, and the AI
        //   features were dead with no explanation and no way back.
        //
        //   The file is now EXCLUDED from cloud backup and device transfer,
        //   so a restored phone simply has no key yet and asks for one, which
        //   takes ten seconds and always works.
        //
        //   And if it is unreadable anyway — a credential reset, an OS
        //   upgrade — it is discarded and remade rather than failing for
        //   ever, and the shooter is told once why they must re-enter it.
        //   "Should not happen" is not a recovery plan.
        //
        // 1.46.0 — correction: the User Guide named Claude throughout.
        //
        //   Its chapter is now "Scoring with an AI service" and every
        //   description is of the service in general. The only place either
        //   is named is the table saying where each key comes from, which is
        //   the one place the reader actually needs the name.
        //
        //   The guide also gained what this week's bugs taught: paste a key
        //   as a single line, keys are kept per service, and keys are left
        //   out of backup on purpose.
        //
        // 1.45.0 — correction: OpenAI would not connect at all. Reported
        //          with the exact error, which named the fault precisely:
        //          "unexpected char 0x0a at 83 in header value".
        //
        //   0x0a is a newline, and index 83 of "Bearer sk-proj-..." is 76
        //   characters into the key. A key pasted from a wrapped display
        //   carries a line break in the MIDDLE of it, and trim() — which is
        //   what the store used — only takes them off the ends. An HTTP
        //   header may not contain a newline, so the request failed before
        //   anything was sent, with an IllegalArgumentException that reached
        //   the user as though the service had refused.
        //
        //   Keys now have ALL whitespace removed, not just the ends, on the
        //   way in and on the way OUT — so a key stored by 1.44.0 is repaired
        //   rather than failing for ever. The dialog says how many characters
        //   it stripped, and a request is refused outright if a key somehow
        //   still carries any, with a message saying to paste it as one line.
        //
        //   Anthropic had the same hazard and had simply not been given a
        //   wrapped key yet.
        //
        // 1.45.0 — feature: a key for EACH service, visible at once.
        //
        //   They were already stored separately in 1.44.0, but the screen
        //   showed only the selected one, so setting a second key looked as
        //   though it had replaced the first. Both are now listed, the button
        //   says which service it will act on, and forgetting one leaves the
        //   other alone.
        //
        // 1.45.0 — correction: the settings screen named Claude throughout.
        //
        //   Reasonable when there was one service and wrong the moment there
        //   were two: a heading reading "Second opinion (Claude)" above a
        //   picker set to OpenAI is a contradiction on its face. The section
        //   is "AI assistance" and every option describes the service in
        //   general. Where a specific console has to be named — where the key
        //   comes from — it is named from the selection, not hard-coded.
        //
        // 1.44.0 — feature: OpenAI alongside Claude. Settings -> AI service.
        //
        //   Both are sent the SAME rectified picture, the same question and
        //   the same hole schema, and both are held to it by the mechanism
        //   their own API provides — a forced tool call for Anthropic, a
        //   json_schema response format with strict set for OpenAI. Nothing
        //   downstream knows or cares which replied, which is the point: a
        //   card scored by either arrives in one shape.
        //
        //   Keys and model choices are kept PER SERVICE, so switching to
        //   compare the two and back does not mean pasting a key in again.
        //   The model list is rebuilt when the service changes, because a
        //   Claude identifier means nothing to OpenAI. Each list is short and
        //   carries an "Other" entry: a list of model names goes stale the
        //   week it is written, and being unable to type a newer one would
        //   strand anyone whose account has moved on.
        //
        //   ON THE UPLOADED SUGGESTION, and where it was followed.
        //
        //   Its normalised coordinates, its structured outputs and its
        //   warning against trusting a vision model to score outright were
        //   already how this app works — and its four-step hybrid pipeline,
        //   arrived at independently, is a fair description of what STS
        //   already does: registration, detection, model as arbiter of the
        //   doubtful, deterministic scoring from measured geometry.
        //
        //   Its overlay-offset caveat does not arise here. The picture sent
        //   is the RECTIFIED card, already on the millimetre grid the plot
        //   draws in, so a fraction maps back with one linear step and there
        //   is no FIT_CENTER letterboxing to compensate for.
        //
        //   WHERE IT WAS NOT FOLLOWED: it advises a backend, so the key never
        //   ships in the APK. Sound advice for a DEVELOPER'S key, which is
        //   what that warning is about — an extracted key spends the
        //   developer's money on everyone's behalf. Here the key is the
        //   SHOOTER'S OWN, entered by them and billing them; a backend would
        //   move it onto someone else's server, add an operating cost to a
        //   free app, and put every user's target photographs through a
        //   third party. It is stored in the Android keystore and never
        //   logged. Anyone wanting the backend model can point the app at
        //   their own by changing one endpoint.
        //
        // 1.43.0 — feature: the Targets preview is square, so a face fills
        //          the width — and the ring numerals appear at last.
        //
        //   THE NUMERALS WERE ALREADY BEING DRAWN. They were being dropped,
        //   by the readability gate that refuses a glyph too small to read.
        //   At the 180 dp preview an ISSF air pistol ring annulus is 24.8 px
        //   on a typical phone, which asks for a 15.4 px glyph against a
        //   17.9 px floor — so every ISSF face lost its numbers at every
        //   screen density. At the size a square box gives, the same annulus
        //   is 41 px and the glyph 26, and they simply appear. The gate was
        //   right; the box was too small. Nothing about the gate changed.
        //
        //   The ring margin went from 2.15x the outer radius to 2.05x as
        //   well: five per cent either side was visible slack once the box
        //   stopped being the constraint.
        //
        //   AND THE LIST SURVIVES, which was the condition attached. A share
        //   of the screen is a guess about everything else on the page — the
        //   parameter table is longer for some faces, and a long name wraps —
        //   so the preview is sized, and then the LIST IS MEASURED and the
        //   preview gives height back if the catalogue came out under 96 dp.
        //   Guessing once and hoping is how the list disappears on somebody
        //   else's phone, and for the silhouette faces the list is the only
        //   way to tell an IPSC target from an IDPA one in a thumbnail.
        //
        // 1.43.0 — correction: the offline stub had View.layoutParams as a
        //          read-only Any?.
        //
        //   So it compiled every caller that READ it and rejected the first
        //   one that resized a view — which is the case the stub exists to
        //   check. A stub weaker than the thing it stands in for hides the
        //   errors it was written to catch; this is the fourth time that has
        //   been true in this project and the note is here to make it the
        //   last.
        //
        // 1.42.0 — correction: the settings screen explained itself instead
        //          of describing itself.
        //
        //   Every option had grown a paragraph — the mechanism, the measured
        //   evidence, the reasoning behind its default. All of it true, none
        //   of it what someone deciding whether to tick a box needs to read
        //   at that moment. Ten options, ten paragraphs, and the list of
        //   options was the thing that got lost.
        //
        //   Each option now carries ONE LINE saying what to expect:
        //
        //       Fewer false hits: rejects printed marks that happen to look
        //       like shots.
        //
        //   with the paragraph one tap away under "More info". Nothing was
        //   deleted — every explanation still exists, it is simply no longer
        //   in the way of the switch it describes.
        //
        //   Worth recording how this happened, because it was not an
        //   accident: each of those paragraphs was written to justify a
        //   default or record a measurement, one release at a time, and each
        //   was reasonable on its own. Documentation belongs in the source
        //   and in the guide; a settings screen is a control panel.
        //
        // 1.41.0 — correction: "the reply was not in the expected form",
        //          reported on Opus 5. The fault was the parsing, not the
        //          model.
        //
        //   The prompt asked for "JSON and nothing else", and a request is
        //   not a guarantee. A model may put a sentence in front of it, wrap
        //   it in a code fence, or — the likeliest cause here — run out of
        //   room part way through and leave an object that never closes. The
        //   parser looked for the first { and the last }, so a truncated
        //   reply failed with a message that said nothing useful about why.
        //
        //   The model now answers THROUGH A FORCED TOOL CALL. The API
        //   assembles the arguments against a schema and hands back a
        //   structured object, so there is no prose to parse and nothing to
        //   go wrong in the parsing. The old text path stays as a fallback
        //   for an account or model where the tool call does not come back.
        //
        //   The budget went from 1500 tokens to 4000 as well: a card with a
        //   dozen holes and a note on each ran the old one out, which is what
        //   made a longer-answering model the one that failed.
        //
        //   And the error now says WHICH failure it was — a reply cut off for
        //   want of room reads differently from one that came back in a shape
        //   the app could not read, and stop_reason is quoted either way.
        //   "Not in the expected form" told the user nothing they could act
        //   on, which is its own defect.
        //
        // 1.40.0 — feature: ONE choice of what scores a card, made on import.
        //
        //   Settings -> "On import, score with:" — Embedded, or Cloud AI.
        //
        //   This replaces three overlapping checkboxes that had accumulated
        //   one request at a time: "enable the button", "override the app"
        //   and "find and score outright". Between them they described states
        //   nobody wanted — overriding an engine that was not running, or
        //   delegating outright while the embedded detector still ran first
        //   and threw its work away. Adding a switch per request is how a
        //   settings screen stops meaning anything.
        //
        //   AND IT NOW RUNS WHERE IT SAYS IT DOES. Cloud AI fires from the
        //   IMPORT screen, in place of the embedded detection, not from a
        //   button on Results afterwards. A setting that says "on import" and
        //   then needs a second action on another screen is not the setting
        //   it claims to be.
        //
        //   Registration stays with the app under both. Without knowing where
        //   the card is and how big it is there is no millimetre grid, and
        //   without a grid nothing can be drawn in the right place. The
        //   picture sent is the RECTIFIED card, already on that grid, so a
        //   fraction of the image maps back with one linear step and the
        //   marks land exactly where the shooter sees them.
        //
        //   Cloud AI falls back to Embedded when no key is set, rather than
        //   importing a photograph and scoring nothing.
        //
        //   The enum is ScoringSource, not ScoringEngine: the scoring package
        //   already owns a ScoringEngine that turns a hole into a score, and
        //   two types a letter apart is a bug waiting to be written.
        //
        // 1.39.0 — feature: Claude can find AND score the card outright.
        //
        //   Settings -> "Let Claude find and score the shots outright". Off by
        //   default. The app's own hole finding is not run at all; the shots
        //   on the plot are Claude's, positions included.
        //
        //   REGISTRATION IS STILL THE APP'S, and that is not a half measure.
        //   Without knowing where the card is and how big it is there is no
        //   millimetre grid, and without a grid nothing can be drawn in the
        //   right place on the photograph or compared with a catalogue face.
        //
        //   AND IT IS WHAT MAKES THE POSITIONS LINE UP. The picture sent is
        //   the RECTIFIED card — already on the grid the plot draws in — so a
        //   fraction of that image maps back to millimetres in one linear
        //   step, with no projection in between. The marks land exactly where
        //   the shooter sees them on their own photograph, which was the
        //   condition attached to this request.
        //
        //   THE APP STILL SCORES THE POSITION IT IS GIVEN, from the ring
        //   geometry, and reports how many of Claude's rings disagree with
        //   it. It does not quietly pick a winner: a disagreement means
        //   either the position or the ring is wrong, and knowing WHICH SHOTS
        //   those are is the whole value of having two answers.
        //
        //   Every shot is recorded as hand-placed, because no position here
        //   was measured. The trade is the app's 0.2 to 1.7 mm for the
        //   several millimetres a vision model gives — on a 10 m face, where
        //   the rings are 8 mm apart, that can be a ring.
        //
        // 1.38.0 — feature: the second opinion can be set to WIN.
        //
        //   Settings -> "Let Claude's answer override the app". Off by
        //   default. With it on the reconciliation is applied without asking:
        //   marks Claude does not see are removed, shots it sees that the app
        //   missed are added, and Claude's POSITIONS are used.
        //
        //   THE ORDER OF OPERATIONS IS THE POINT, and it is not what was
        //   first asked for. The request was to send the app's own hit list
        //   to Claude and have it judge without being biased by it. Those two
        //   cannot both happen: anything in the prompt anchors the answer, so
        //   fourteen positions in front of the model is fourteen positions it
        //   will tend to confirm — which is exactly the failure that would
        //   make the feature worthless. So the call stays IMAGE ONLY, the
        //   model never sees what the app thought, and the comparison is done
        //   locally afterwards, where it is arithmetic and cannot be wrong.
        //
        //   WHAT IT COSTS, stated rather than buried. A vision model places a
        //   hole to a few per cent of the image, several millimetres on a
        //   170 mm card; the app measures one it can see to between 0.2 and
        //   1.7 mm. On a 10 m air pistol face the rings are 8 mm apart, so a
        //   shot placed from Claude's coordinates can be a ring out. Shots
        //   placed this way are recorded as HAND-PLACED, so the Results list
        //   and any report show them as positions that were not measured —
        //   the setting may trade accuracy, but it may not hide that it did.
        //
        //   WHAT IT BUYS is the other half of the trade, and it is real: the
        //   app's measured failure is over-detection, printing read as shots,
        //   and counting is the one thing the model does better. On T0002 it
        //   counts seven and is right.
        //
        //   A targeted second call — sending the disputed marks back and
        //   asking "shot, or printing?" — is deliberately NOT built yet. It
        //   would double the cost per card and give a reason rather than a
        //   count, and one unbiased pass already finds the false marks.
        //
        // 1.37.0 — correction: removal removed ONE. Reported from real use.
        //
        //   Asked to delete nine marks, the app deleted one and said nothing.
        //   [ScoringSession.reindex] renumbers every survivor by REPLACING it
        //   with a copy carrying a new index, so the moment the first shot was
        //   gone every other Shot the caller still held had stopped being
        //   equal to anything in the list — and MutableList.remove is
        //   equality-based, so each later call quietly removed nothing.
        //
        //   [ScoringSession.removeShots] now takes them all at once and
        //   matches by IDENTITY, not equality: two genuinely distinct shots
        //   can compare equal after a reindex, and the caller means these
        //   objects and no others.
        //
        //   AND THE TWO COUNTS DISAGREED. The button counted the marks Claude
        //   had not mentioned; the dialog counted those PLUS everything beyond
        //   the rings. So it offered to review seven and then asked to remove
        //   nine. The removable set is now decided once, in the reconciler,
        //   and the button and the dialog both read it.
        //
        // 1.37.0 — correction: the second-opinion dialog said far too much.
        //
        //   It explained the method, the millimetre accuracy of each path and
        //   the token count. None of that helps at the moment of deciding. It
        //   now says what differs and what the choices are:
        //
        //       Claude sees 7 shots. The app has marked 14.
        //       9 marked that Claude does not see, 9 of them outside the
        //       scoring rings.
        //
        //   with the caveat kept only where it changes a decision — on the
        //   removal confirmation, where a shot Claude missed and a shot the
        //   app invented genuinely do look the same.
        //
        // 1.36.0 — correction: "off" now means off. Three faults, all
        //          reported from real use on T0002.
        //
        //   1. FALSE MARKS OUTSIDE THE SCORING AREA WITH THE SWITCH OFF.
        //      The limit was `outer * 1.10`, which on a 10 m air pistol face
        //      is 85.5 mm — eight millimetres BEYOND the outermost ring. The
        //      two false marks on that card sit at 81.2 and 82.8 mm, so
        //      switching "also find shots that missed the rings" off changed
        //      nothing about them. The limit is now the furthest a hole's
        //      CENTRE can be and still have its edge touch the outer ring:
        //      outer radius plus half a gauge, 80.0 mm on that face. Past
        //      that a mark cannot score whatever it is.
        //
        //      MEASURED on T0002, misses off: 5 marks, all five real, NONE
        //      beyond the rings, score 19 against a hand-scored 19. Exact.
        //
        //   2. REMOVAL WAS TOO TIMID. It offered only the marks with no
        //      Claude spot within twelve millimetres, so a false mark that
        //      happened to sit near a real shot counted as "supported" and
        //      was never offered — accepting every removal still left most of
        //      them. When Claude has counted fewer shots than the app has
        //      marked, everything beyond the scoring rings is now a candidate,
        //      whether or not Claude mentioned it.
        //
        //   3. THE DUPLICATE GUARD WAS A GAUGE WIDE, and the re-measure can
        //      settle a millimetre or two from where the app's own sweep put
        //      the same hole. At exactly one gauge that gap was enough to add
        //      a second mark to a shot already there. Now one and a half.
        //
        //   WHAT IS STILL TRUE, and now quantified: with misses ON the same
        //   card gives 16 marks, of which 7 are real and 9 are printing —
        //   the footer, the crest, the roundel — spread from 81 to 94 mm and
        //   INTERLEAVED with the two genuine misses at 92.4 and 96.6. No
        //   threshold on radius, contrast or profile separates them; all
        //   three were tried and measured. Counting is the only thing that
        //   has ever arbitrated it, which is what Claude is for here.
        //
        //   Practical advice, and the guide should say it: leave the switch
        //   off unless you want to see where a flyer went, and expect to
        //   prune when it is on.
        //
        // 1.35.0 — correction: the second opinion could only make an
        //          OVER-DETECTED card worse. Reported from real use.
        //
        //   On T0002 the app marked FOURTEEN, several of them printing
        //   outside the scoring rings. Claude counted SEVEN, which is the
        //   right answer — that card was hand-scored at 9, 6, 2, 1, 1 and two
        //   misses. The reconciler then offered to ADD three more and nothing
        //   else, so the plot went from fourteen wrong to seventeen wrong.
        //
        //   THE FAULT WAS ASYMMETRY. The reconciler computed the marks Claude
        //   did not see, and only mentioned them in a sentence. Over-detection
        //   is this app's measured failure mode, so an aid that can add and
        //   cannot remove is pointed the wrong way. Removal is now an offered
        //   action, and when the app has found MORE than Claude it is the
        //   PRIMARY button — the summary says so too, in the first sentence.
        //
        //   Not automatic, and the dialog says why: Claude missing a real shot
        //   and the app inventing one look identical from here. What it can do
        //   is say WHERE the disputed marks are, and offer to remove only the
        //   ones outside the scoring rings — which is where every false mark
        //   this app has produced on a test card has been found.
        //
        //   AND A DUPLICATE GUARD, which was the other half of the same
        //   report. A suggestion is matched against existing shots on the
        //   MEASURED position, not on Claude's: the model places a hole
        //   several millimetres out, so a suggestion for a shot the app had
        //   already found could miss it on raw coordinates and be added a
        //   second time. Once re-measured it has snapped onto the real hole,
        //   and two marks on one hole are unmistakable.
        //
        //   WORTH KNOWING for anyone reading the T0002 numbers: fourteen marks
        //   needs "also find shots that missed the rings" switched on, which
        //   extends the search to 1.35x the outer ring — into the footer text,
        //   the club crest and the ISSF roundel. That switch has carried this
        //   known limitation since 1.26.0. Claude counting the shots is the
        //   first thing that has been able to arbitrate it, because how many
        //   holes are on a card is a counting question and counting is what a
        //   vision model is good at.
        //
        // 1.34.0 — correction: a shot in the TEN RING was being dropped, and
        //          the reason was a threshold doing the wrong job.
        //
        //   [FocusedRemeasure] refused the shot at (-1.8, 12.7) — inside the
        //   aiming mark, a nine — at every offset it was pointed with. The
        //   shot at dead centre, a ten, survived. The two sat either side of
        //   an arbitrary line and nothing said so.
        //
        //   WHAT IT ACTUALLY WAS. Core pixels were those more than a FIXED 30
        //   levels from the local background. Inside the mark a hole stands a
        //   hundred levels or more above the ink and drags a bright halo with
        //   it: measured on that shot, the eight radial bands read 134, 130,
        //   121, 107, 77, 64, 61 and 48 against a background of 27. Every
        //   band but the last cleared 30, so the "hole" measured 3.20 gauge
        //   areas against a ceiling of 3.00 and was thrown out — by seven per
        //   cent. The centre shot measured 2.89 and passed. Neither number
        //   was about the hole; both were about how much ink was nearby.
        //
        //   THE FIX IS TO STOP USING AN ABSOLUTE LEVEL. The edge of the hole
        //   is now taken at HALF ITS OWN HEIGHT above the background, which
        //   is the standard way to size a feature without its contrast
        //   setting its apparent size, with the old margin kept only as a
        //   floor so a flat window still yields nothing. The peak is a 90th
        //   percentile rather than the brightest pixel, because one specular
        //   glint off torn paper would otherwise halve the threshold for the
        //   whole hole.
        //
        //   MEASURED, card A frame 6, suggestions offset to imitate a vision
        //   model's error:
        //     offset 0 mm   0.1  1.7  1.0  1.5  2.2  0.5 mm — six of six
        //     offset 3 mm   0.6  1.8  1.8  1.5  2.2  0.5 mm — six of six
        //   Before: five of six, with the nine in the black never confirmed
        //   at any offset. All four suggestions dropped on blank card are
        //   still refused, so nothing was traded away for this.
        //
        //   The two worstresiduals are the shot outside the rings at 2.2 mm,
        //   which scores nothing either way, and the nine at 1.7 mm — checked,
        //   and not enough to move it across the 13.75 mm ring line.
        //
        //   NO REGRESSION: card A frame 6 still detects six of six and scores
        //   33 against a truth of 33.
        //
        // 1.33.0 — feature: a region holding more than one shot is SPLIT
        //          instead of thrown away.
        //
        //   Measured on card B, which carries four pairs at 4.5, 3.1, 2.3 and
        //   2.0 mm apart:
        //     frame        before        after      truth
        //     B2 (1 pair)  0, score 0    2, score 11   2, 11   exact
        //     B3           0, score 0    3, score 16   3, 16   exact
        //   The 4.5 mm pair now separates correctly and both shots score.
        //
        //   WHY IT WAS LOST. The cluster was always FOUND — one blob, every
        //   frame — and then rejected by whichever single-shot gate happened
        //   to catch it: on profile, on size, on shape, a different one each
        //   time. So the split is asked for BEFORE those gates, and the parts
        //   are not put through the puncture test at all: a part of a peanut
        //   has no isolated radial profile, and demanding one is exactly what
        //   rejected the two-shot frame.
        //
        //   A SPLIT IS AN INFERENCE, and is marked as one — merged = true, and
        //   a confidence of 0.40 against 1.0 for a measured shot.
        //
        //   ONE GUARD EARNED ITS PLACE IMMEDIATELY. With nothing standing
        //   between a blob and being reported as several shots, the first
        //   frame of card C turned one hole and two patches into SIX shots,
        //   scoring 45 where the truth was 10. A region must now carry twice
        //   the detection threshold in mean deviation before it may be split.
        //
        //   AND A REGION TOO BIG IS REFUSED RATHER THAN GUESSED AT.
        //   [MergedHoles] separates along one axis, which is right for a pair
        //   and meaningless for a rosette of eight. Reporting three shots
        //   where there are eight is worse than reporting none, because a
        //   count that looks plausible will be believed.
        //
        // 1.33.0 — measurement: the two-photograph difference method does NOT
        //          work on hand-held re-photographs, and the splitter is
        //          therefore necessary rather than a fallback.
        //
        //   The null test settles it: two photographs of the SAME UNPUNCHED
        //   card differ by 3 phantom holes on card C and 10 on card B.
        //   Registering each frame independently made it worse, 5 and 6 — so
        //   it is not camera movement. At the rectified plane's 0.625 mm/px a
        //   printed ring line is half a pixel wide, and no alignment a ring
        //   fit can give makes those lines cancel. This was tested BEFORE
        //   building the splitter, on the theory that differencing would
        //   separate merged shots for free. It does not, and the hour spent
        //   checking saved the effort being spent on the wrong thing.
        //
        //   Not tested: the same method from a FIXED MOUNT with the lighting
        //   undisturbed, which is what the app has always asked for.
        //
        //   CARD C IS CONTAMINATED BY ITS OWN SHEET and cannot be used as a
        //   measurement until regenerated. Its ten punch crosses sit within
        //   10 mm of centre, so they overlap into a dense grey patch INSIDE
        //   the aiming mark — and grey on black is brighter than its
        //   background, which is exactly the test that finds a hole there. On
        //   cards A and B the marks are spread out and mostly on paper, where
        //   a 0.15 mm line is removed by the opening. The sheet generator
        //   needs the marks moved off the black for that card.
        //
        // 1.32.0 — feature: a suggestion from Claude is now MEASURED before
        //          it becomes a shot.
        //
        //   The gap this closes was mine. 1.30.0 was described as re-searching
        //   where the model pointed; it did not. Accepting a suggestion planted
        //   a shot AT the model's coordinate, which carries a few per cent of
        //   the image — several millimetres on a 170 mm card, up to a whole
        //   ring — against 0.2 to 1.7 mm for a hole the app finds itself.
        //
        //   Now the suggestion says only WHERE TO LOOK. [FocusedRemeasure]
        //   measures what is actually there and the measurement decides where
        //   the shot goes. Measured on card A frame 6, feeding it suggestions
        //   deliberately offset from the truth to imitate the model's error:
        //
        //     offset given    0 mm    2 mm    4 mm    6 mm
        //     centre shot     0.2     0.2     1.3     3.2
        //     boundary shot   0.6     0.5     0.6     1.0
        //
        //   It CONVERGES: pointed 4 mm wrong at the shot straddling the black
        //   edge, it still lands 0.6 mm from truth. Four of the six shots
        //   confirm within about 1.5 mm.
        //
        //   AND IT REFUSES. Four suggestions dropped on blank card — one of
        //   them inside the aiming mark — are all declined and nothing is
        //   placed. Before the puncture test was applied here, two of those
        //   four were placed as shots. That is the property that matters: the
        //   model can be wrong that a hole exists, and when it is, the app
        //   must say so rather than score it.
        //
        //   TWO THINGS IT STILL GETS WRONG, on the same card: one shot inside
        //   the black is never confirmed at all, and the shot outside the
        //   rings measures a consistent 2.2 mm off. So this is better than
        //   what it replaces — which was no measurement whatever — and it is
        //   not as good as the detector's own finds. Suggested shots stay
        //   marked MANUAL for that reason.
        //
        //   Printed ring lines are excluded from the core. Inside the aiming
        //   mark the rings are printed WHITE, which is exactly the "brighter
        //   than the background" test that finds a hole there, and the two
        //   central shots were swallowing the 10 and 9 rings and being thrown
        //   out as far too large.
        //
        // 1.32.0 — feature: the shooter's photograph is rectified four times
        //          finer than the detection grid.
        //
        //   [rectify] runs at eight pixels per gauge because it allocates
        //   integral images over the whole card — a memory bound, not an
        //   accuracy choice — and rectifyColour was sharing it. That made the
        //   photograph 272 px across a 170 mm card: what the shooter sees
        //   under "My photo", and the only image the Results screen has, so it
        //   also capped how well anything could be re-measured there. Nothing
        //   is allocated per pixel for a picture, so it now runs at 32 px per
        //   gauge for about 1.2 MP.
        //
        // 1.32.0 — correction: the release build still would not link.
        //
        //   R8 moved on from the JSR-305 annotations to Tink's KeysDownloader,
        //   which wants the Google HTTP client and Joda-Time to fetch JWT keys
        //   over the network. Nothing in encrypted storage calls it and
        //   neither library ships. Warned away, with a note on why the broad
        //   -keep on Tink is what surfaces these at all and why narrowing it
        //   would be worse: R8 could then strip a key manager Tink loads by
        //   name, which is a crash on first use rather than a build failure.
        //
        // 1.31.0 — feature: the background is estimated LOCALLY, and card A
        //          now scores exactly right.
        //
        //   MEASURED, on the user's punched card A frame with six holes:
        //     one level per zone   5 of 6 found, score 26 against a truth of 33
        //     local background     6 of 6 found, score 33 — exact
        //   The shot it had never found is the one 3.5 mm inside the black
        //   edge; its error went from 26.3 mm (not found at all) to 0.9 mm.
        //
        //   WHY A GLOBAL LEVEL COULD NOT WORK. On card B, photographed
        //   unretouched, the paper reads 28 to 40 levels darker at the foot of
        //   the sheet than at the head. The detection threshold is 28. So at
        //   one end of the card the background was wrong by more than the
        //   entire threshold — one end reads as a hole everywhere and the
        //   other as nothing at all. That is not a tuning problem.
        //
        //   THE TWO ZONES SURVIVE, and that is the part worth explaining. A
        //   single local estimate would smear the black mark's edge across a
        //   window several holes wide and make that edge the strongest
        //   "hole" on the card. So each side is estimated without ever
        //   looking at the other — and the zones come from the REGISTRATION,
        //   which knows in millimetres where the black is, so the boundary is
        //   a known fact rather than something inferred from pixels.
        //
        //   And each pixel is now judged against its OWN zone. Deciding the
        //   polarity of a whole hole from the zone its CENTRE fell in is what
        //   lost the boundary shot: half of it was measured against the wrong
        //   side. Costs about 40 ms on a 1200 px frame.
        //
        //   CARD B FOUND THE NEXT FAULT, which this does not fix. Of its four
        //   pairs the detector finds the two TIGHTEST (2.3 and 2.0 mm apart)
        //   as one blob each, and misses the two WIDEST (4.5 and 3.1 mm)
        //   entirely — because a wide pair is elongated, and the shape gate
        //   throws it out at 1.9. [MergedHoles] exists and knows how to split
        //   a region holding two shots, but it is wired into the rectified
        //   detector only, not into [SourceHoleDetector]. Score on that card
        //   is 10 against a truth of 41. That is the next job.
        //
        // 1.31.0 — correction: the RELEASE build would not link.
        //
        //   R8 refused to shrink because androidx.security:security-crypto
        //   pulls in Google Tink, which is compiled against JSR-305
        //   annotations Android does not ship. Nothing dereferences them at
        //   run time, so they are warned away rather than satisfied with
        //   another dependency; Tink's key managers, which it loads by name,
        //   are kept. Invisible until assembleRelease, because the debug
        //   build does not run R8 at all.
        //
        //   Also a nullable smart-cast that the offline type-check caught and
        //   the earlier package did not carry.
        //
        // 1.30.0 — feature: a SECOND OPINION from Claude, on demand, that is
        //          not allowed to score.
        //
        //   The premise it was proposed on does not hold, and the design
        //   follows from that. The card scored correctly earlier was not read
        //   by eye: the holes were counted by eye and then MEASURED — circles
        //   fitted to the printed rings, scale by least squares, each radius
        //   computed. The 6 on that card turns on 0.8 mm, and nothing looking
        //   at a JPEG resolves 0.8 mm. A vision model's positions carry a few
        //   per cent of the image, several millimetres on a 170 mm card,
        //   against 0.2 to 1.7 mm for a hole the app has measured.
        //
        //   So the division of labour is by what each is good at. The model
        //   is asked which face, how many holes, roughly where, and whether
        //   the photograph is usable at all — recognition and counting. The
        //   app keeps ring fitting, scale, centroid and the gauge rule. Our
        //   measured failure is RECALL, not precision, and counting is
        //   exactly what the model does well.
        //
        //   NOTHING IT SAYS BECOMES A SCORE. A position it reports is only a
        //   place to look again; anything unconfirmed is offered to the
        //   shooter and, if accepted, recorded as MANUAL — the same flag a
        //   tap on the plot sets — so no report can present a suggested shot
        //   as a measured one. Both callers now go through one
        //   placeManualShot() so they cannot drift apart.
        //
        //   Coordinates are requested as FRACTIONS OF THE IMAGE. The model
        //   cannot know the scale, and asking it for millimetres would invite
        //   it to invent them. The image sent is the RECTIFIED photograph,
        //   which is already on the millimetre grid, so a fraction maps to
        //   millimetres in one linear step with no projection to add error.
        //
        //   The key is from the Anthropic Console and is NOT a Claude.ai
        //   password — the first thing anyone will get wrong, so the dialog
        //   says so. It is held in EncryptedSharedPreferences, and if the
        //   device will not provide that the key is NOT written in the clear
        //   as a fallback: storage fails and the user is told.
        //
        //   Off by default, on demand from a button, and useless without a
        //   connection — which most ranges do not have.
        //
        // 1.30.0 — correction: two failures found on the punched test card.
        //
        //   A HARMONIC LOCK. Image A2 fitted a ring pitch of 16.4 px where
        //   the truth is 49 — exactly a third — and scored the card 9 instead
        //   of 19 with no error anywhere. Now refused. The first version of
        //   that check was BLIND to it: MarkOutline had latched onto
        //   something also a third of the right size, so the scale-free ratio
        //   between mark and pitch came out at 3.40 against an expected 3.72
        //   and looked fine. A scale-free test cannot see a consistent
        //   rescaling of both its terms. BlackMarkDetector measures the mark
        //   independently of the ladder — 182 px against a pitch of 16.36 is
        //   a ratio of 11.1, which is 199 per cent out.
        //
        //   THE MONOTONIC PROFILE TEST WAS DOING NO GOOD. Measured against
        //   blank paper, printed ring lines, numerals and the black field,
        //   every one scores 1.00 monotonic exactly as real holes do — it
        //   separates a hole from print essentially not at all. What it did
        //   reliably was reject real shots: three of six on the punched card,
        //   because a drilled hole has a bright burr a pellet hole does not,
        //   and a five-level wobble from paper grain costs a whole step out
        //   of seven. The CONTRAST floor was doing the work all along (holes
        //   44 to 98 levels, printed features under 5). Demoted to a sanity
        //   floor. Its window also shrank from 1.25 gauges to 1.0, because at
        //   1.25 a shot at dead centre samples the printed 10-ring circle.
        //
        //   NOT VERIFIED IN THIS SESSION: the offline suite stalled part-way
        //   through on a slow sandbox. Gates, UI type-check and every suite
        //   up to and including PunctureCheckTest passed; SourceHoleDetector,
        //   T0002Corpus, FixedSight and SecondOpinion did not get to run.
        //
        // 1.29.0 — feature: two sights that cannot be clicked, and more of a
        //          catalogue window given over to the catalogue.
        //
        //   "Built-in iron sight" and "No sight" are not just two more rows.
        //   Every sight in the app until now could be adjusted by counting
        //   something, and three things had to change before a sight that
        //   cannot be would give an honest answer.
        //
        //   ClickUnit.NONE, so the absence of a click is STATED rather than
        //   inferred from a missing number. Adding it deliberately broke the
        //   exhaustive `when`s that describe a click, which is what those
        //   `when`s are for — a seventh unit is a compile error rather than a
        //   sight that silently labels itself wrongly.
        //
        //   hasClicks now asks the CLICK, not the kind of sight. It used to
        //   read `clickMrad > 0 && sightType != OPEN_SIGHTS`, i.e. no open
        //   sight has clicks — which is false of exactly the sights this app
        //   is for. The Morini, Pardini and Walther target rear sights in the
        //   catalogue are open sights and every one is click-adjustable to a
        //   published millimetre at a stated distance. The error was hidden
        //   because ScopeCatalog.toScopeProfile labelled EVERY catalogue pick
        //   a telescopic sight, diopters and red dots included; it now maps
        //   the family to the type, which is also what stops a bare barrel
        //   being offered turret clicks.
        //
        //   THE SIGHT RADIUS IS LEFT UNSET, on purpose. It runs from about
        //   150 mm on a service pistol to 700 mm on an air rifle, and a
        //   plausible default would produce a confident instruction to move
        //   the rear sight by the wrong amount. Unset, the app says so and
        //   asks for the measurement — a ruler's work, and then exact.
        //
        //   With no sight at all the app reports the group as a HOLD-OFF
        //   instead. The first draft of that sentence had both directions
        //   reversed: shots landing high need a LOWER aim, and moveY is
        //   already negative in that case. A test pins both axes.
        //
        // 1.29.0 — correction: catalogue screens gave too much room to their
        //          furniture and not enough to the list.
        //
        //   Closed spinners 48dp -> 34dp (they are labels, not tap targets;
        //   the whole widget takes the tap). Dropdown rows 48dp -> 42dp —
        //   shortened rather than squeezed, because those ARE tap targets and
        //   below about 40dp a moving finger on a range starts to miss. The
        //   face preview on Targets 230dp -> 180dp: it is there to say which
        //   face this is, not to be scored on. The shared bottom bar fixed at
        //   52dp rather than left to wrap.
        //
        //   Footer buttons use a new Sts.ActionButton.Dense at 34dp, applied
        //   ONLY to the catalogue screens. Not to Sts.ActionButton itself:
        //   the Results controls were made a uniform 44dp deliberately, and
        //   shrinking every button in the app to gain rows on two screens is
        //   the wrong trade.
        //
        //   The catalogue dialogs get the freed height back on the list —
        //   ammunition and sights 320 -> 400dp, firearms 280 -> 360dp.
        //
        // 1.28.0 — feature: the test card is now PART OF THE APP, and every
        //          build is scored against it.
        //
        //   The one real photograph with an answer known independently of this
        //   code: an ISSF 10 m Air Pistol card scored by hand — centre from
        //   circles fitted to the printed rings, scale from a least-squares
        //   fit of those against the catalogue (pitch recovered as 7.999 mm
        //   where the truth is 8.000) — giving 9, 6, 2, 1, 1 and two shots off
        //   the rings, 19 points from 7 shots.
        //
        //   Six tests hold it. Two of them exist because they are the two
        //   shots that have already caught real bugs:
        //
        //     THE 9 sits inside the black, where the colour detection channel
        //     is saturated and carries nothing. Every build up to 1.26.0
        //     missed it and scored the card 10 instead of 19 — quietly,
        //     because every stage did exactly what it was told.
        //
        //     THE 6 has its centre at 38.55 mm, OUTSIDE the 6-ring at 37.75.
        //     By centre alone it is a 5; the hole's edge reaches 36.3 and
        //     breaks the line, and ISSF scores what the hole touches. The test
        //     asserts both readings explicitly, so anything that starts
        //     scoring from centres will fail rather than quietly drop a point.
        //
        //   Stored as the two 760 px planes the detector consumes, not as a
        //   JPEG: unit tests have no Android image decoder. 760 px because the
        //   full 1536 px original gives an identical score, so the smaller one
        //   is the honest choice for something that runs on every build. About
        //   500 kB, gzipped, and it runs in roughly 130 ms.
        //
        // 1.28.0 — correction: `continue` inside an inline lambda, which needs
        //          Kotlin 2.2 while this project builds on 2.1.
        //
        //   The worst kind of error: it compiles locally and fails in CI. The
        //   offline type-checker cannot catch it either, because it uses
        //   whatever kotlinc is installed rather than the one Gradle pins. So
        //   a seventh gate now looks for break and continue inside run, let,
        //   also, apply, forEach and the rest, and it was verified by putting
        //   the error back and watching it complain.
        //
        // 1.27.0 — feature: shots are found in the PHOTOGRAPH, and the shot
        //          inside the aiming mark is found at last. 10 -> 19 on the
        //          user's card, which is the hand-scored truth exactly.
        //
        //   THE CAUSE, after three wrong guesses. The rectified detector was
        //   losing the 9 — nearly half the card. It was not resolution:
        //   measured at 8, 12, 16, 24 and 32 rectified pixels per gauge the
        //   answer is identical. It was not the ring-pitch scale: that claim
        //   was made and retracted in 1.26.0. It was the COLOUR CHANNEL. That
        //   channel measures how far each pixel sits from the paper's own
        //   colour, which is what makes a hole stand out on a tinted card —
        //   but inside a black aiming mark it measures nothing, because ink
        //   and the grey torn fibres of a hole through it are equally unlike
        //   paper, and both saturate at zero. In that channel the 9 occupies
        //   8 per cent of its own window, widest solid run six pixels, against
        //   a gauge of forty-one. In plain luminance the same hole is a 38.2
        //   px core. So each zone is now read with the signal that carries
        //   information there: luminance inside the mark, colour outside.
        //
        //   AND AN OPENING, NOT A BLUR. The first port smoothed the deviation
        //   over half a gauge, reasoning that a ring line is three pixels wide
        //   and a hole forty. That reasoning was taken from luminance, where a
        //   line stands 90 levels off the paper; in the colour channel it
        //   stands 220, and three parts in twenty-one of 220 clears the
        //   threshold. The lines survived, fused with every hole they touched,
        //   and the fused blobs were then rejected as oversized — 41 of 56, no
        //   shot inside the rings reported at all. A grey-scale opening cannot
        //   fail that way: it deletes anything narrower than its window
        //   whatever the contrast, so width and contrast stop trading off.
        //
        //   MEASURED, against 9, 6, 2, 1, 1 and two misses = 19:
        //     rectified detector       10   (4 of 5 hits, 1 false positive)
        //     this, at 1536 px         19   (5 of 5)
        //     this, at  760 px         19   (5 of 5, and no false marks)
        //   On by default, and the only experimental switch that is, because
        //   it is the only one measured to change a score.
        //
        //   KNOWN LIMITATION, not solved: spurious marks can still appear
        //   BEYOND the outermost ring, where everything printed lives. They
        //   score zero and cannot move a total. Two ways of removing them were
        //   tried and both failed — contrast does not separate them (false 94,
        //   110, 139 against genuine 113 and 169) and neither does demanding a
        //   perfect radial profile, because they have one.
        //
        //   Radii still read about a millimetre short at the outer rings
        //   (66.4 against 67.6 measured by hand). Not enough to change a ring
        //   on this card. Unexplained, and next.
        //
        // 1.26.0 — feature: the detector can be asked whether a candidate has
        //          the PROFILE of a puncture, and to look for the shots that
        //          missed the rings.
        //
        //   Both come from scoring the user's card by hand, independently of
        //   the app, and then running the shipped pipeline on the same image
        //   to see where the two differ. By hand: 9, 6, 2, 1, 1 and two
        //   misses, 19 points from 7 shots. Shipped: 4 real hits, ONE FALSE
        //   POSITIVE (a piece of the maker's footer, at 82.77 mm), and the 9
        //   in the black missed entirely.
        //
        //   PUNCTURE TEST. A hole takes the most material out of its centre,
        //   so it gets steadily lighter outwards until it reaches the paper;
        //   a printed roundel or a letter does not. Measured out to 1.25
        //   gauges: all seven real holes 1.00 monotonic with 57 to 145 levels
        //   of contrast, the ISSF roundel 0.71 and 15, the club crest 0.43
        //   and -29, the footer text 0.57 and 1. Switched on, it removed the
        //   false positive and kept every real hole.
        //
        //   MISSES. Absolute detection has always been confined to the
        //   scoring area, deliberately, because outside it is all card
        //   furniture. But two of these seven shots were out there, and a
        //   plot that silently drops the worst shots of a string
        //   misrepresents the group. Optional, cannot change a total, and it
        //   turns the puncture test on with itself because that region is
        //   entirely print.
        //
        //   A CLAIM MADE AND RETRACTED, recorded because the shape of the
        //   mistake matters. RingFinder reported a ring pitch of 75.54 px
        //   where fitting circles to the same rings gave 73.95, and that 2.1
        //   per cent was taken for a scale error read straight off every
        //   score. It was not: RingFit coordinates are in the CORRECTED
        //   frame, which for this card is 1575 px wide against a 1536 px
        //   source — 2.5 per cent. Two numbers measured in different frames,
        //   differing by exactly the amount the frames differ by, and the
        //   conclusion drawn was that one of them was wrong. Measured
        //   properly both recover the 8 mm pitch to a thousandth of a
        //   millimetre. RingFamilyFit ships anyway, OFF, because its per-ring
        //   residuals say whether a card is flat where the ladder gives one
        //   averaged figure — which may earn its place on angled cards.
        //
        //   STILL WRONG, and not fixed here: the 9 in the black is still
        //   missed, and reported radii still sit about 1.2 mm inside the
        //   measured truth on some shots. The rectified plane runs at eight
        //   pixels per gauge whatever the source resolution, so a hole
        //   photographed 42 px across is judged as an 8 px blob. That is the
        //   next thing to look at, and it wants the range corpus.
        //
        // 1.25.0 — feature: the camera ADOPTS the face it identifies, and
        //          the plot no longer loses your zoom when you nudge a shot.
        //
        //   MEASURED, on the user's own T0002. The card is ISSF 10 m Air
        //   Pistol: its black measures 3.723 ring pitches across, and that
        //   face expects 3.719 — 0.1% out. Against ISSF 10 m Air Rifle, which
        //   expects 6.100, the same card is 39% out. That is why the SAME
        //   image scored from a photograph and failed from the camera: Import
        //   has identified and adopted the face on load since 1.22.0, so it
        //   quietly corrected the selection; Session only ever COMPLAINED,
        //   leaving registration scaled against a face 39% wrong, which puts
        //   every hole outside the detector's size gates. No hits, no error.
        //
        //   The ratio was not the doubt it looked like. Run over the same
        //   card at 1536, 1280, 1080, 900, 760, 640 and 512 px it reads
        //   3.723, 3.733, 3.730, 3.724, 3.720, 3.723, 3.710 — so neither the
        //   analysis resolution nor the YUV colour channel was to blame, and
        //   both were checked before the face was.
        //
        //   Guarded against the flip-flop this could otherwise cause: a face
        //   the app has moved away from is not silently adopted again in the
        //   same session, and choosing one by hand clears that record, since
        //   the user may deliberately be going back to it.
        //
        //   Both callers now go through adoptFace(), because the spinner and
        //   selectedFace must move together — the 1.18.0 bug where the screen
        //   showed one face and the scorer used another came from setting one
        //   and not the other.
        //
        //   ZOOM: TargetPlotView reset zoom and pan on every assignment to
        //   .face, and Results reassigns it after every edit. So nudging a
        //   shot by a tenth of a millimetre threw you back to the default
        //   zoom, at the exact moment you were zoomed in BECAUSE you were
        //   nudging. It now resets only when the face really changes.
        //
        // 1.24.0 — feature: the guide says continuously whether the card
        //          matches the selected face.
        //
        //   A word in the corner — "Match OK", "Wrong target face",
        //   "Checking", "Aim at the target" — and the rings change with it.
        //   A message that fires once is the wrong shape for this: a shooter
        //   lining a phone up is watching the preview, not a notification
        //   that appeared while they were still walking to the point.
        //
        //   ENCODED THREE WAYS AT ONCE, and deliberately not by colour alone.
        //   Green would be the obvious choice and it breaks the night-red
        //   theme, whose entire purpose is preserving dark adaptation — a
        //   green line would be the one bright non-red thing on that screen.
        //   So under night-red the hue is left alone and the state is carried
        //   by the line style and the word; under the other themes the colour
        //   changes as well. Solid means matched, dashed means doubtful,
        //   finely dashed means wrong. That is also what a colour-blind
        //   shooter needs, on a small screen, in daylight.
        //
        //   The check runs from the frame stream, at most every 2.5 s, and
        //   NOT AT ALL while live detection is armed: those frames belong to
        //   the detector, and a second full ring fit on the same thread would
        //   drop frames — a dropped frame is a shot the persistence rule
        //   never sees.
        //
        //   The full explanation is still spoken, but ONCE per change of
        //   verdict rather than on every check, so a minute spent lining up
        //   does not produce the same warning thirty times.
        //
        // 1.23.1 — correction: the app now WARNS when the card in front of
        //          the camera is not the face that is selected.
        //
        //   The ring guide makes a mismatch visible, but only to someone who
        //   knows to look — and the natural response to circles that will not
        //   line up is to walk until they do. That cannot work, because the
        //   rings are drawn at the face's own proportions and changing
        //   distance resizes them all together. At best it wastes the
        //   shooter's time; at worst they decide it is close enough, fire a
        //   card, and get a score wrong by however much the two faces differ.
        //
        //   So the app looks as well. When the guide is switched on, and
        //   whenever the face changes, it fits the rings on the live preview
        //   and compares them with the selected face — the same two checks
        //   used at registration, the scale-free ratio and the ranking by
        //   fitted pitch. Run on the analysis thread, advisory only: a
        //   preview is not a registration and a card half out of frame should
        //   not produce an accusation.
        //
        //   The cost of a wrong face is now stated in ONE place and quoted
        //   everywhere the app doubts it, rather than three vaguer variants:
        //   the face sets millimetres per pixel, so its error goes straight
        //   into every radius and therefore every score, and because the
        //   detector sizes what it looks for from the same number, a face
        //   wrong by enough finds no hits at all rather than wrong ones. The
        //   message says plainly not to force the guide by moving closer, and
        //   why that cannot work.
        //
        // 1.23.0 — feature: the selected face's rings as an alignment guide.
        //
        //   Four guides now: nothing, the simple crosshair, the RINGS of the
        //   selected face, or both. Size adjustable, because the distance to
        //   the card is set by the range and not by the app.
        //
        //   THE RINGS VERIFY THE FACE, and that is the strongest argument for
        //   them rather than the obvious one. They are drawn at the face's own
        //   RATIOS, so a card whose rings sit at different proportions will
        //   not line up however far the shooter moves: scaling changes every
        //   circle together and cannot change the spacing between them. A
        //   mismatch is therefore visible through the viewfinder before a
        //   shot is fired, rather than afterwards as a score that is quietly
        //   wrong. Selecting the wrong face remains the single largest cause
        //   of nothing being detected at all, and until now nothing in the
        //   app could catch it before the fact.
        //
        //   That property also guards against the obvious misuse. A guide the
        //   shooter can resize freely would otherwise invite forcing a match
        //   with the wrong face by walking closer; fixing the ratios makes
        //   that impossible.
        //
        //   Lining the rings up squares the camera to the card as well, which
        //   matters because perspective is the one error the scorer cannot
        //   fully undo: de-foreshortening recovers about half of what a
        //   square-on view would have given at 30 to 40 degrees, and the rest
        //   is a projective term a single affine correction cannot represent.
        //   Not taking the error beats correcting it.
        //
        //   The guide is a guide, not a requirement. Modest tilt is corrected
        //   perfectly well and a shooter should not be made to fuss.
        //
        //   AimGuide lives in its own file rather than inside CrosshairView:
        //   ScaleSettings stores the choice and is pure logic that the
        //   offline harness compiles without a framework, and an enum tucked
        //   inside a View drags the whole of android.view in behind it.
        //
        // 1.22.1 — correction: the WEAKER registration was the automatic one.
        //
        //   On loading a photograph the app ran the aiming-mark path, which
        //   takes the scale from the black mark multiplied by the ratio of
        //   whichever face happens to be SELECTED — so it is only ever as
        //   right as that selection, and selecting the wrong face is the
        //   single largest cause of nothing being detected at all (1.12.0).
        //
        //   It now identifies instead: fits the ring family, measures the
        //   pitch over five to nine rings, lets the fit say WHICH face it is
        //   rather than assuming, and cross-checks pitch against mark. That
        //   is the same route as the "Identify and register" button, and the
        //   0.2-point difference between the two paths reported from the
        //   field is exactly this — a scale disagreement of about one per
        //   cent, not a bug in either.
        //
        //   The mark path remains, as a FALLBACK rather than the default,
        //   which is the right way round. It is used automatically when the
        //   rings cannot be fitted at all, and when the face has no even
        //   pitch to fit — the practical and service faces put their scoring
        //   zones at unequal spacings by design, and for those the mark is
        //   the only measurement there is. That second case previously left
        //   the card unregistered with a message and no fallback.
        //
        //   Both buttons stay, so either method can still be forced by hand.
        //
        // 1.22.0 — feature: Results markers, photo/template swap, a brighter
        //          crosshair, and the rings that were found but not used.
        //
        //   SHOT MARKERS are half as large again as the hole, hollow, with a
        //   gapped cross on the centre. A filled disc at true size covers the
        //   very hole it marks, which defeats the photo view: the point of
        //   showing the photograph is to check whether a marker sits where a
        //   hole actually is.
        //
        //   PHOTO AND TEMPLATE are now alternatives, not layers. Drawing the
        //   printed template over the shooter's own card put two sets of
        //   rings a pixel or two apart, and the eye reads that as a
        //   registration error whether or not there is one. Shots, group and
        //   point of aim go on top of whichever is shown.
        //
        //   THE CROSSHAIR was a 2 px line at 82 per cent alpha, which
        //   disappears against a white card in sunlight. Now full alpha, 3.5
        //   px, a heavier dark halo for contrast either way, longer arms, and
        //   a bright core. The core is a LIGHTENED accent rather than white,
        //   so night-red stays red — a white core would put back the one
        //   bright thing that theme exists to remove.
        //
        //   RINGS FOUND BUT NOT USED are now drawn dashed on the registration
        //   overlay, and counted in the log. "Detected and left out of the
        //   family" and "never detected at all" looked identical and mean
        //   quite different things: the first is a ladder that chose a
        //   subset, the second is a ring the detector could not see. This
        //   answers, on the card itself, which is happening.
        //
        // 1.21.0 — feature: merged shots, the shot-count check, hole-centre
        //          precision as its own metric, and stage timings.
        //
        //   TWO SHOTS THROUGH ONE PLACE (H1). A connected-component detector
        //   sees them as one region, and the size and roundness gates then
        //   REJECTED that region — losing BOTH shots rather than one. At 10 m
        //   a good shooter puts most of a card through the same few
        //   millimetres, so this is the app's own use case failing.
        //
        //   MergedHoles splits such a region along its own long axis, at the
        //   peaks of the detector response inside it. Measured on synthetic
        //   pairs at known separations:
        //       separation   parts   centre error
        //         0.3 gauge    1     (refused, correctly)
        //         0.5 gauge    1     (refused, correctly)
        //         0.7 gauge    2      0.31 px
        //         0.9 gauge    2      0.05 px
        //         1.2 gauge    2      0.01 px
        //   Below about two thirds of a pellet the holes genuinely overlap
        //   and it declines to guess: a shot invented on paper is a score the
        //   shooter did not fire, which is worse than one missed. A split
        //   shot carries a lower confidence and a `merged` flag for the same
        //   reason — it is an inference, not a measurement.
        //
        //   SHOT COUNT (C1). The rule set has always known the string length
        //   and nothing looked at it. Nine holes where ten were fired is a
        //   missed detection and eleven is a false one. ADVISORY only: the
        //   obvious next step of lowering the threshold until the expected
        //   number appears would manufacture shots on demand and turn the
        //   detector into a machine that always agrees with the course of
        //   fire.
        //
        //   HOLE-CENTRE PRECISION (H2), measured separately from detection
        //   rate for the first time. They fail differently and only one is
        //   visible: a missed hole shows on the plot, a hole half a
        //   millimetre out looks normal and flips any shot near a boundary.
        //   Worst centre error across fifteen sub-pixel positions is 0.35 px,
        //   which at the rectified resolution is about 0.2 mm — under a tenth
        //   of a 10 m air rifle ring.
        //
        //   STAGE TIMINGS in the log: Hough, mark outline, shape, correction
        //   and ladder, separately, with the frame size. Registration
        //   performance has twice been diagnosed by guessing at which stage
        //   was slow; now the field log says.
        //
        //   NOT DONE: the full ring-family homography. It is written and
        //   validated offline — it recovered 39.82 degrees against a true 40
        //   — but it is worth only about a further quarter of the residual
        //   after the ellipse correction, and it depends on the face being
        //   right, which turns a wrong identification into a confident wrong
        //   score. It needs the range photographs before it earns a place.
        //
        // 1.20.0 — feature: resolution discipline, a bound on the ladder
        //          search, and the tilt-axis wedge behind a switch.
        //
        //   RESOLUTION DISCIPLINE. MarkOutline ran at FULL resolution while
        //   everything around it did not — RingFinder works at 700 px and
        //   HoughCentre at 420. On a phone photograph arriving at 3000 px
        //   that meant five threshold passes, each with a morphological
        //   closing of four separable passes, over nine megapixels. It now
        //   works at 700 px and returns the outline in source pixels.
        //
        //   Measured on one card at four sizes, 460 to 3000 px. The mark
        //   radius scales exactly linearly (84.0, 164.8, 329.6, 549.3 against
        //   expected factors of 1.96, 2.00, 1.67), and the SCALE-FREE ratio of
        //   mark radius to ring pitch holds to 0.24 per cent from 900 px
        //   upward. Time is now bounded rather than growing with pixels: 999
        //   ms at nine megapixels against 1311 ms at 0.8.
        //
        //   A HANG, found while measuring the above and worth its own note.
        //   fitLadder tries every PAIR of candidates against nine divisors, so
        //   its cost grows with the CUBE of the candidate list. On a warped
        //   753 px frame — smaller than images that finish in a second — the
        //   pooled list grew enough that a single registration ran for
        //   MINUTES. The pool is now capped at 24, cut by peak strength so
        //   what survives is the clearest evidence rather than whatever
        //   happened to be innermost. No catalogue face has more than a dozen
        //   rings. The same frames now finish in 0.8 to 1.5 s.
        //
        //   THE TILT-AXIS WEDGE, off by default, under Settings. Along the
        //   axis a card tilts about, depth does not change, so the scale there
        //   is exact — which is why the fitted pitch drifts monotonically with
        //   tilt while the true pitch cannot. Measuring only in that direction
        //   should remove the drift.
        //
        //   Measured on four frames it is NOT a clear win: unchanged on one
        //   (37.00 to 37.06), better on one in the sense that it REFUSED a fit
        //   that was 15 per cent wrong rather than returning it, and worse on
        //   a third (31.01 to 32.50). It ships off, with a switch, until real
        //   range photographs can settle it.
        //
        //   NOT ADDED, because it was already there: persistence for live
        //   scoring. LiveHitDetector has required three consecutive frames
        //   since it was written, with a stability tolerance, a global-change
        //   veto, deduplication against the accepted set, and optional
        //   acoustic gating. Proposing it as missing was my error.
        //
        // 1.19.2 — toolchain: the activities can finally be compiled
        //          offline, closing the gap that let three failures reach CI.
        //
        //   tools/offline/typecheck_ui.sh compiles EVERY source file in the
        //   app — 62 of them, activities and custom views included — against:
        //     - view-binding classes GENERATED from the real layouts, so a
        //       field that is not in the layout is not in the stub either;
        //     - an R GENERATED from the real resources, for the same reason;
        //     - a hand-written slice of the Android framework, roughly 25
        //       files across android.view, android.widget, android.graphics,
        //       androidx and the CameraX and Material pieces the app touches.
        //
        //   It runs nothing. The point is only that the compiler resolves
        //   every name, which is exactly what the three CI failures were.
        //
        //   THE STUBS ARE DELIBERATELY NARROW. Each class carries only the
        //   members the app actually uses, because a stub that answered to
        //   anything would resolve a typo as readily as a real name — the
        //   lesson from the JUnit shim in 1.10.1 and the Paint stub in
        //   1.12.2, both of which were too generous and hid the very failure
        //   they existed to catch. Using a new framework API therefore means
        //   adding a line to tools/offline/Stub*.kt.
        //
        //   Getting there took the error count from 2490 to zero over seven
        //   rounds. Two things had to change in the APP rather than the
        //   stubs, and both are improvements in their own right:
        //     - three adapters disagreed about whether getView's parent was
        //       nullable. Android never passes null there; they now agree.
        //       Kotlin cannot express a Java platform type, so a stub cannot
        //       accept both spellings at once.
        //
        //   RUN IT SEPARATELY from run.sh. Two compiler invocations back to
        //   back contend badly — chained, the check went from forty seconds
        //   to several minutes.
        //
        //   WHAT IT STILL DOES NOT COVER: resource merging, manifests, data
        //   binding generation and R8. Gradle remains the authority, and this
        //   is a filter in front of it, not a replacement.
        //
        // 1.19.1 — correction: two compile errors that have been in the
        //          tree since 1.18.0, through two releases.
        //
        //   Both came from copying the resolution spinner out of
        //   ImportActivity into SessionActivity and keeping the original's
        //   helper names: onSelectedIndex, which is private to another file,
        //   and startCamera(), which here is called startCameraIfPermitted.
        //
        //   THE OFFLINE HARNESS CANNOT SEE THIS. The activities are excluded
        //   from it because they need the whole Android framework to compile,
        //   so anything wrong inside one reaches CI untouched. That is now
        //   three times.
        //
        //   NEW GATE, and a sound one rather than a heuristic: a private
        //   function is visible only inside its own file, so an unqualified
        //   call to one from a DIFFERENT file is always an error. It reports
        //   exactly the failure above. Verified by putting the error back —
        //   "onSelectedIndex() is private to ProfileActivity.kt and cannot be
        //   called here" — and then restoring.
        //
        //   Its first run found three FALSE positives, all now excluded:
        //   add() inside a buildList, which is the standard library's and not
        //   Logger's; and onFrame in FrameSource, which is a lambda parameter
        //   rather than SessionActivity's private function of that name.
        //
        //   WHAT IT STILL CANNOT CATCH: a call to a name that exists nowhere
        //   at all, which is what startCamera() was. That needs a compiler and
        //   an android.jar. Generating view-binding stubs from the layouts
        //   would make the activities compilable offline and close this
        //   properly; it is the obvious next step for the toolchain.
        //
        // 1.19.0 — feature: 9x19 service pistols and factory loads.
        //
        //   FIREARMS. Beretta 92X Performance and Performance Defensive
        //   first, as asked, then 92X Full Size, Centurion, Compact and the
        //   92FS; Glock 17, 17L, 19, 19X, 26, 34, 43, 43X, 45, 47 and 48.
        //   Barrel lengths are the makers' published figures. Twist is 1 turn
        //   in 250 mm for both — 9.84 inches — which is what Beretta and
        //   Glock each publish for 9 mm. Zeroed at 25 m rather than the 10 m
        //   used for air or the 50 m for rimfire.
        //
        //   AMMUNITION. Fiocchi Range Dynamics in 115, 124 and 147 gr;
        //   Federal American Eagle, Syntech Range, Syntech Training Match,
        //   Syntech Action Pistol and HST; CCI Blazer Brass in 115, 124 and
        //   147 gr. Weights and muzzle velocities are the makers' published
        //   figures.
        //
        //   BALLISTIC COEFFICIENTS ARE NOT PUBLISHED by any of these three
        //   for pistol ammunition, so those are the conventional values for
        //   the bullet weight and shape. That is a real difference from the
        //   rest of the catalogue, whose BCs come from the maker, and it is
        //   recorded in the source rather than glossed over. It matters very
        //   little here — between BC 0.14 and 0.19 the drop at 25 m differs
        //   by well under a millimetre — and would matter at 100 m, where
        //   none of these loads belongs.
        //
        //   NOT a complete listing of every product line. Loads appear only
        //   where a published velocity could be found for that exact product;
        //   several defensive lines are left out rather than invented.
        //
        //   THREE DEFECTS THE NEW TESTS FOUND, all pre-existing:
        //     - A centrefire PISTOL whose model name lacked the word "pistol"
        //       was classed as a centrefire RIFLE, and would have taken a
        //       rifle's default sight height and zero.
        //     - Twist was displayed through toInt(), so 1 turn in 250 mm read
        //       as "1:9" — a rate nothing is rifled at. The CZ Shadow 2 and
        //       the Tanfoglio Stock II were already being shown wrongly.
        //     - The calibre regex, being case-insensitive, read "Glock 19X
        //       9x19" as calibre "19X 9": it found the model number, an
        //       upper-case X and the leading 9 before reaching the calibre.
        //
        // 1.18.1 — correction and feature: in-app capture actually exists
        //          now, and it is guarded.
        //
        //   THE ANSWER TO "DO YOU CAPTURE AT FULL RESOLUTION" WAS NO, BECAUSE
        //   THE APP DID NOT CAPTURE AT ALL. There was no ImageCapture use case
        //   and no VideoCapture use case; the only camera output was the
        //   analysis stream, and "Score the target now" read a preview frame
        //   from it. So there was nothing to set to 4K — 1.18.0's resolution
        //   selector governs that analysis stream, which was all there was.
        //
        //   ImageCapture is now bound at HIGHEST_AVAILABLE_STRATEGY and
        //   MAXIMIZE_QUALITY. A still comes off the sensor at its full size,
        //   commonly 12 megapixels against the analysis stream's 2 — some 2.4
        //   times the linear resolution on every hole, which are the smallest
        //   things this app measures.
        //
        //   TWO COUPLINGS THAT WOULD HAVE MADE THIS SILENTLY WRONG, both found
        //   before shipping and both worth recording:
        //
        //   1. The registration maps millimetres to ANALYSIS-frame pixels. A
        //      still has different dimensions, so putting one through that
        //      registration maps the wrong pixels — a plausible score computed
        //      from the wrong geometry. The registration is now rescaled for
        //      the still, and then CHECKED against the printed rings in that
        //      still before use; a failure falls back to the analysis frame
        //      and says so. Rescaling assumes the two streams frame the same
        //      scene, which is usual and not guaranteed, so it is verified
        //      rather than trusted.
        //
        //   2. LiveHitDetector rectifies the reference AND every later frame
        //      through one registration, and later frames arrive from the
        //      analysis stream. A full-resolution reference would therefore be
        //      differenced against frames it is not aligned with. The
        //      reference deliberately stays on the analysis stream, and "score
        //      now" only reaches for a photograph when there is no reference
        //      to align with.
        //
        //   Still not implemented: VIDEO capture. camera-video is on the
        //   classpath and unused. Recording, storage and frame extraction is a
        //   larger piece than belongs in this change, and the phone's own
        //   camera app records perfectly well for collecting test material.
        //
        // 1.18.0 — feature: the camera is held still, and its resolution
        //          can be chosen.
        //
        //   NEITHER WAS BEING DONE. Nothing in the app touched CameraControl,
        //   so exposure, white balance and focus ran free the whole time.
        //   For the differential path that is close to fatal: differencing
        //   assumes two frames of one card differ only where a shot arrived,
        //   and a camera that re-meters between them changes EVERY pixel by
        //   more than the 40 or so levels a hole is worth. Re-focusing is
        //   worse again — the lens moves, the field of view shifts slightly
        //   on nearly every phone, and the registration silently goes stale.
        //   Auto white balance moves the colour channel that hole detection
        //   is measured in.
        //
        //   All three are now locked, automatically, at the moment a card is
        //   registered — which is exactly when the framing has settled — and
        //   there is a button under the preview to release them when the
        //   light changes for real. Focus is locked by metering once with
        //   auto-cancel disabled rather than by switching AF off, which on
        //   many devices then needs the lens driven by hand and parks at
        //   infinity if you do not.
        //
        //   ANALYSIS RESOLUTION is selectable: 720p, 1080p, 1440p or 4K, with
        //   CameraX falling back to the nearest supported size. It was fixed
        //   at 1080p, so live scoring never saw more than that however good
        //   the phone. Changing it rebinds the camera and CLEARS the
        //   registration, because that was measured in the old frame's pixels.
        //
        //   Not changed: the photo path still imports from the gallery rather
        //   than capturing, so a still comes in at whatever the camera app
        //   produced, capped by ImageLoader at 3000 px on the long side.
        //
        // 1.17.0 — feature: face identification stabilised, and the two
        //          largest costs in registration removed.
        //
        //   T1 WAS THE LIMITING FACTOR, and measurably so: across six tilt
        //   angles of the SAME card the identified face changed up to four
        //   times. That matters more since 1.16.0, because the scale now uses
        //   the face's own black-to-pitch ratio.
        //
        //   The cause is not noise. Black radius over ring pitch CANNOT
        //   separate this catalogue at any precision — 4.00 for ISSF 25/50 m
        //   Precision Pistol, 4.00 for the German 100 m face, 4.01 for the
        //   NRA A-23/5; 6.10 for 10 m Air Rifle against 6.00 for 300 m Rifle.
        //   Those are the same shape at different sizes.
        //
        //   DISTANCE separates every one of those collisions, and the session
        //   has known its distance all along from the rule set. Candidates
        //   are now filtered by it before the ratio is compared, falling back
        //   to the whole catalogue if nothing survives — a face used at a
        //   distance it was not drawn for should give a worse answer, not no
        //   answer.
        //
        //   HYSTERESIS. A face already in use is kept unless a rival beats it
        //   by more than the margin the fitted pitch itself wanders by. That
        //   is what stops the answer flapping between frames of one target.
        //
        //   MEASURED, distinct faces chosen across six tilts of each card:
        //       card   before   after
        //       t01      1        1     (and 300 m -> 10 m, i.e. plausible)
        //       t02      2        2
        //       t03      4        1
        //       t04      3        2
        //   Ten identifications down to six, and no 300 m face proposed for a
        //   10 m session.
        //
        //   PERFORMANCE, first two items by expected gain. Registration was
        //   taking around 20 s a frame in the harness, which had begun to
        //   obstruct the accuracy work.
        //     - fitLadder called leastSquares INSIDE the innermost loop of an
        //       O(n^3) search for the aiming-mark test, sorting a map and
        //       allocating every time. The anchor already defines the line, so
        //       no fit is needed there; the least-squares runs once, on the
        //       winner.
        //     - The diagnostic second pass, which re-ran the whole fit without
        //       the shape correction purely so the log could compare, is now
        //       off by default.
        //   Measured over six frames: 591 ms each down to 335 ms, with an
        //   identical pitch on every frame — same answers, less work.
        //
        // 1.16.0 — feature: the scale is now measured two independent ways
        //          and cross-checked. Accuracy, not speed.
        //
        //   Every scoring error is proportional to millimetres-per-pixel, so
        //   it is the most consequential number the app measures. Until now
        //   it came from one source: the spacing of the fitted ring ladder.
        //   That is precise when the rings are found — 0 to 1.5 per cent on a
        //   square-on card — and it degrades badly when they are not.
        //
        //   The aiming mark gives a completely separate reading: its measured
        //   radius, divided by the black-to-pitch ratio the catalogue states
        //   for that face. One high-contrast boundary instead of a family of
        //   faint lines, and no ladder to lose.
        //
        //   MEASURED, four real cards warped by tilts of 0 to 25 degrees with
        //   the face held fixed, so this is scale consistency and not
        //   identification noise. Rotation about an axis through the centre
        //   leaves depth there unchanged, so the true scale is IDENTICAL at
        //   every angle and all of the spread is error:
        //
        //       card    ladder     mark    cross-check
        //       t01       2.7%     1.0%       1.6%
        //       t02      13.5%     1.1%       1.1%
        //       t03      21.8%     1.4%       2.9%
        //       t04      90.5%     1.9%       1.4%
        //
        //   And on absolute accuracy, measured offline against the two cards
        //   whose faces are known, the mark reading alone gave -0.8% and
        //   +0.7% against the true pitch — as good as the ladder at its best,
        //   from a single measurement with no search.
        //
        //   CROSS_CHECK is the default: within noise of the mark alone on
        //   consistency, and it additionally REPORTS when the two disagree,
        //   which on these images fired on 3 to 6 cases out of 6 and is
        //   usually the sign of a wrong face. When they agree to within 6 per
        //   cent their mean is used, because averaging two independent
        //   readings of comparable accuracy beats either.
        //
        //   Selectable under Settings > Detection algorithms, per the
        //   development rule that every new algorithm gets a switch until the
        //   accuracy is good enough to fix the choice.
        //
        //   WHAT THIS IS NOT. It needs the face, so it cannot identify one —
        //   used that way it would be circular, since identification already
        //   uses the mark. It verifies a face that has already been chosen.
        //
        //   NOT INTEGRATED, and why: template alignment by iterative
        //   optimisation (P2a). Six cost functions were prototyped offline
        //   and every one degraded a good seed — intensity correlation
        //   collapses onto the black; summed gradient locks the half-pitch
        //   harmonic (-49%, -47%); freeing the centre took one card from
        //   -4.3% to +27%; and refining against the exact known radii still
        //   shrank the scale by ~4% on both cards tested. The value in that
        //   line of work turned out to be the arithmetic seed above, not the
        //   optimisation.
        //
        //   KNOWN, and deferred on instruction: registration has become slow
        //   enough to obstruct measurement — around 20 s per frame at 800 px
        //   in the offline harness. The likely cause is the aiming-mark
        //   constraint added to fitLadder in 1.11.0, which calls a least
        //   squares fit inside the innermost loop of an O(n^3) search.
        //
        // 1.15.0 — feature: the interface follows the theme, and buttons
        //          are consistent.
        //   - 18 buttons across Import, Session, Targets, Rules and Settings
        //     had no style at all and were rendering at the platform's own
        //     size and in capitals, next to styled ones at 14sp. Every button
        //     in the app now carries a style, and all three button styles are
        //     14sp.
        //   - The status text over the viewfinder and over an imported photo
        //     was hardcoded #FFFFFF, and the registration overlay was a fixed
        //     gold box with white handles and white labels. All of it takes
        //     colorAccent now.
        //   - The crosshair follows the theme too. This one is not cosmetic:
        //     under night-red a white crosshair is the single brightest thing
        //     on a screen whose whole purpose is preserving dark adaptation,
        //     and it undoes what the theme is for.
        //   - Catalogue list rows lost the platform's listPreferredItemHeight
        //     minimum and half their vertical padding, so more of a long
        //     firearm or ammunition list fits on screen.
        //
        //   DELIBERATELY NOT THEMED, so it is not filed as a miss:
        //     - The target plot. Its colours are representational — paper
        //       #FAFAF5, ink #1A1A1A — because it depicts a real card. Tinting
        //       it red would make the drawing wrong rather than themed.
        //     - The detection markers on the registration overlay. They are a
        //       different colour from the box because they mean something
        //       different: what the app FOUND, as against what the user is
        //       placing. Recolouring them to the accent would lose that.
        //     - The dark scrims behind overlay text, which exist to make
        //       anything legible over a photograph of unknown brightness.
        //
        // 1.14.1 — correction: two presentation fixes, and a third static
        //          gate that found a real error while being written.
        //   - The Stage time and Notes fields on Results were on the
        //     platform's default EditText size, several points larger than
        //     the 12-13sp body text around them, so they read as headings.
        //     Both are 13sp now: with the body range, but not the smallest
        //     thing on the screen, which text being typed into should not be.
        //   - The selected target face on the Targets screen lists its
        //     dimensions in a two-column table, both columns left aligned,
        //     sharing item_param_row.xml with the rules screen. They ran
        //     inline as prose before — "Card 80 x 80 mm", "Aiming black 30.5
        //     mm" — so nothing lined up and the numbers could not be read
        //     down the column. The outer ring diameter is listed too; it was
        //     not shown at all, and it is the figure the scale depends on.
        //
        //   NEW GATE: android widget types used by simple name must be
        //   imported. Kotlin has no implicit android.* imports, and neither
        //   the view-binding gate nor the offline harness can see this — the
        //   activities are excluded from the harness because they need the
        //   whole framework to compile.
        //
        //   Written because this very change introduced one:
        //   findViewById<TextView> in TargetActivity, which had no TextView
        //   import. The first version of the gate passed it, because the file
        //   used android.widget.TextView fully qualified elsewhere and the
        //   gate treated that as sufficient. It is not — a qualified use does
        //   not make the bare name available. Corrected to look for
        //   SIMPLE-NAME uses specifically, which then reported it.
        //
        // 1.14.0 — feature: justified description text, and an aligned
        //          parameter table on the rules screen.
        //   - Sixteen descriptive paragraphs are now fully justified with
        //     android:justificationMode, across Home, Session, Import,
        //     Results and the two catalogue dialogs. Available unconditionally
        //     because minSdk is 26, which is the release it was added in.
        //     Applied to PROSE only: justification stretches inter-word
        //     spacing to reach the right margin, which on a two-word heading
        //     or a one-line field label reads as a mistake rather than as
        //     typesetting.
        //   - Rule parameters are a two-column table, both columns left
        //     aligned. They were lines of a single TextView with the names
        //     padded out with spaces, which only lines the values up in a
        //     monospaced font — the same defect the home screen's setup block
        //     had, and it wandered by several characters in the app's
        //     proportional face. Left rather than right alignment on the
        //     values on purpose: a ragged left edge is what stops a column
        //     being scannable.
        //   - The rows are INFLATED from item_param_row.xml rather than
        //     constructed in Kotlin. Passing a ContextThemeWrapper to a View
        //     constructor sets the theme used to resolve attributes; it does
        //     not apply the style's own attributes, so rows built that way
        //     compile, run, and render completely unstyled.
        //
        // 1.13.0 — feature: interface changes across Home, Session,
        //          Results, Targets, Settings and Import.
        //   - Home no longer carries the upload-photo and live-session
        //     buttons. Both remain reachable — live scoring is a bottom-nav
        //     tab and Import is linked from it — and removing the duplicates
        //     lifts the active setup, the one thing worth confirming before
        //     firing, back above the fold on a small phone.
        //   - The active setup is a two-column TABLE now. It was one TextView
        //     with the labels padded out with spaces, which only aligns the
        //     colons in a monospaced font; in the app's proportional face
        //     they wandered by several characters.
        //   - Session and Targets: every remaining flat text action is a real
        //     button. Results had none left.
        //   - Equal button heights in the Results "Shots" section and the
        //     Settings "Profile sets" section.
        //   - Import reopens showing the last photo scored rather than a
        //     black rectangle. Only the URI is stored, with durable read
        //     permission taken where the picker grants it, so nothing is
        //     duplicated; if the file has gone the screen simply starts empty.
        //
        //   BUG, and a silent one. On Session, currentFace() preferred a
        //   selectedFace field over the spinner, and the code that switches
        //   the spinner after identifying a target suppressed its own
        //   listener — which was the only thing that updated that field. So
        //   the screen showed the identified face while every score was
        //   computed against the previous one, with nothing to indicate it.
        //   The field is now set alongside the spinner, and the spinner
        //   outranks it, so what is displayed is what is scored.
        //
        //   NEW GATE: tools/kotlin_checks.py now verifies that every
        //   binding.<id> exists in that screen's layout, following <include>.
        //   View binding reports a missing id against the GENERATED class,
        //   some way from the layout edit that caused it, and renaming a
        //   control mid-interface-work is the usual way in. Written, found to
        //   catch NOTHING because it built the layout name as "activitymain"
        //   instead of "activity_main" and so skipped every file, then fixed
        //   and verified by deleting a control and referencing it again.
        //
        // 1.12.2 — correction: NameWrapTest asserted nothing and said so
        //          only in CI.
        //   - Under plain unit tests android.jar is stubbed and, with
        //     unitTests.isReturnDefaultValues = true, Paint.measureText
        //     returns 0.0f. The test measured with a real Paint, so every
        //     string "fitted", nothing was wrapped, and the logic under test
        //     never ran.
        //   - NameWrap.wrapAtDash now takes a PREDICATE, with the Paint form
        //     as a thin overload over it. The decision is pure string logic
        //     and only the measurement needs Android; keeping them apart is
        //     what makes the decision testable.
        //   - The offline harness's Paint stub measured text properly, which
        //     is why this passed there and failed here. It now returns 0.0f
        //     like the real one. Same lesson as the JUnit shim in 1.10.1: a
        //     stub more capable than the environment it stands in for hides
        //     exactly the failures it exists to catch. Verified by running
        //     the old formulation against the corrected stub — it now fails
        //     locally too.
        //
        // 1.12.1 — correction: seven interface changes, all reported.
        //   - The shot controls are now two rows of identically sized
        //     buttons. They were weighted with wrap_content heights, so a
        //     label that wrapped to two lines made its own button taller than
        //     its neighbours; labels are short now for the same reason.
        //   - DELETE for a detected shot. It acts on the SELECTED shot rather
        //     than the last one, because detection order has nothing to do
        //     with shooting order and "the last one" would be the wrong shot
        //     as often as not.
        //   - A centred crosshair over the viewfinder. Worth more than
        //     tidiness here: the flatter the card sits in frame, the less the
        //     ring fit has to correct, and residual perspective is the one
        //     error the scorer cannot fully undo.
        //   - Every remaining text action in Settings is a real button, and
        //     its section headings went from 16sp to 19sp — they were
        //     competing with the body text they introduce rather than
        //     standing above it.
        //   - Firearm, ammunition and sight lists break a name at its dash
        //     when it will not fit on one line, so the names line up down the
        //     left and the qualifiers sit underneath. Measured at draw time
        //     against the row's real width and the user's own font scale,
        //     and only broken when the break is actually needed.
        //   - The home screen shows only the part before the dash. A full
        //     catalogue label carries type, calibre, barrel and twist, which
        //     wrapped that aligned column into an unreadable block.
        //
        // 1.12.0 — feature and correction: the real reason hole detection
        //          "generally fails", plus the photo overlay and real buttons.
        //
        //   THE FACE WAS THE PROBLEM, NOT THE DETECTOR. The target face sets
        //   millimetres per pixel, the radius of the scoring area and which
        //   region counts as black. Register against the wrong face and the
        //   rectified card comes out at the wrong scale, so every hole falls
        //   outside the detector's size gates and NOTHING is found — with no
        //   error raised anywhere, because each stage did what it was told.
        //
        //   Measured on the two real cards supplied, absolute detection,
        //   ground truth five shots each:
        //        face used                       o03      o04
        //        identified from the picture     4        5
        //        ISSF 10 m Air Rifle forced      0        0
        //        ISSF 300 m Rifle forced        43       88
        //
        //   That is exactly the reported symptom: the one-button route
        //   identifies the face from the photograph, and registering by hand
        //   did not check it at all. Both routes now check, two ways:
        //     - a SCALE-FREE ratio, black width in ring widths, which can
        //       judge a face without first trusting it for the scale. Catches
        //       the gross case: 32 to 39 per cent out on the cards above.
        //     - ranking every catalogue face by the fitted ring pitch, which
        //       separates faces of similar proportions at different sizes.
        //   Five of six wrong faces are caught. The sixth is honest: on one
        //   card the right face fits to 2.86 per cent and ISSF 25/50 m
        //   Precision Pistol to 3.13, and ring proportions genuinely do not
        //   separate them.
        //
        //   ALSO FIXED: since the de-foreshortening went in at 1.10.0 every
        //   coordinate in a RingFit has been in the CORRECTED frame, and the
        //   registration overlay was drawing them straight onto the original
        //   photograph. The box and ring markers sat up to nine pixels off on
        //   a mildly angled card and further as the angle grew, so what the
        //   user saw disagreed with what had been registered — and nudging
        //   the box by hand started from the wrong place. That is very
        //   probably why adjusting the box by hand made things worse.
        //
        //   NEW: Results can show the hits on the SHOOTER'S OWN PHOTOGRAPH
        //   rather than only on the template. The photo is rectified onto the
        //   same millimetre grid as everything else, so tapping to add a shot
        //   and dragging one to a new place work over it unchanged. This is
        //   the only view in which a MISSED hole is visible at all: on the
        //   template an undetected hole leaves nothing behind to notice.
        //
        //   NEW: Add shot, Move shots, Show whole card, Reset view, Export
        //   CSV, Clear all shots and Finish session were flat coloured text
        //   and read as labels rather than controls. They are real buttons
        //   now. The same mistake had already been reported once, for the
        //   photo-import entry points.
        //
        // 1.11.0 — feature: the ring-pitch ladder now returns the same scale
        //          for the same target photographed at different angles.
        //
        //   The defect, from 1.10.1: pitch came back as 10.4, 36.1 and 7.6 px
        //   for one target at three tilts. Four causes, each measured:
        //
        //   1. ONE PERCENTILE CANNOT SEE EVERY RING. The radial profile was
        //      read at the 25th percentile only, which finds dark rings on
        //      light paper and is BLIND to the white rings printed inside the
        //      black aiming mark — where rings 7 to 10 live. On a real target
        //      the 25th-percentile profile read exactly 0 for every radius
        //      from 0 to 124 px: the whole mark, flat, with three rings in it.
        //      The profile is now read at 0.25, 0.50 and 0.90 from a single
        //      histogram and the candidates pooled.
        //
        //   2. INLIER_TOLERANCE 0.16 admitted a peak nearly 6 px off its rung.
        //      Residual perspective SPLITS an outer ring into two shoulder
        //      peaks, both were admitted, and the refit was dragged between
        //      them. Now 0.10.
        //
        //   3. NOTHING CROSS-CHECKED THE PITCH. The aiming mark is now used
        //      as independent evidence: its radius over the pitch must lie in
        //      2.4 to 8.6, which is the range spanned by every face in the
        //      catalogue (measured: 3.00 to 7.03). This is what rejects a
        //      ladder at half or twice the true pitch — one target was
        //      returning 18.8 px where the mark implied 37.
        //
        //   4. THE MARK ITSELF WAS NOT STABLE. MarkOutline took the first
        //      threshold that produced a compact blob, so the same card at
        //      two angles gave mark radii of 39.6 and 114.0 px. It now
        //      evaluates every threshold and keeps the largest compact
        //      region. It also CLOSES the dark mask first: the rings printed
        //      inside the mark are light lines that stop a flood fill dead,
        //      and on a synthetic face this returned the ten-ring disc
        //      instead of the mark. That had gone unnoticed only because thin
        //      anti-aliased lines on a photograph let the fill leak past.
        //
        //   MEASURED, on four real targets warped by angles chosen in advance.
        //   Rotation about an axis through the centre leaves depth unchanged
        //   there, so the true pitch is IDENTICAL at every tilt and any
        //   spread is error. Spread over 0-15 degrees, which is what a
        //   roughly square-on photograph produces:
        //
        //        target    before        after
        //        t01       (n/a)          7.0%
        //        t02       47.7%          9.2%
        //        t03       71.9%          7.7%
        //        t04      >100%           4.8%
        //
        //   A CHECKED ASSUMPTION THAT TURNED OUT TO BE FALSE, recorded
        //   because it nearly shipped: the black edge lands exactly on a ring
        //   boundary on ten of the twelve applicable catalogue faces, and on
        //   the ISSF 50 m Rifle face — and the German 50 m Kleinkaliber face
        //   that copies it — it sits 0.375 of a ring away by design. A hard
        //   rung constraint, which was written and working, would have made
        //   both unscoreable. It is now a confidence factor with a floor, and
        //   two tests pin the property so it cannot be re-assumed.
        //
        //   STILL OPEN. The remaining 5-9 per cent drifts monotonically with
        //   tilt, which points at a specific cause: the radial profile
        //   averages over ALL bearings, including those the residual
        //   perspective distorts, whereas along the tilt axis depth does not
        //   change at all and the scale there is exactly uniform. Restricting
        //   the profile to a wedge about the fitted major axis should remove
        //   it. Written and NOT shipped: it could not be measured properly
        //   inside this session, and an unvalidated change to the scale is
        //   the worst kind to guess at.
        //
        // 1.10.1 — correction: the unit test source set had never once been
        //          compiled, and failed the moment it could be.
        //   - RingFinderTest asserted assertEquals(0xFE, r.toInt(), 6): three
        //     Ints. JUnit's three-argument form is (double, double, double)
        //     and Kotlin does not widen Int to Double for overload
        //     resolution, so it matched nothing. Now doubles.
        //   - This was reachable only because 1.10.0 was the first release
        //     whose MAIN source compiled; every earlier CI run stopped at
        //     compileReleaseKotlin and never reached the tests.
        //   - tools/offline/run.sh compiles and runs the whole test source
        //     set without Gradle or the Android SDK, in about a minute, using
        //     the kotlin-compiler package from npm. All 110 tests pass.
        //     Its JUnit shim mirrors the real Assert overload set exactly;
        //     an earlier, more permissive shim accepted the very call CI
        //     rejected, which is how this reached CI at all.
        //
        // 1.10.0 — feature: direct least-squares ellipse fitting on the ring
        //          edges, and an evidence-based choice between it and a circle.
        //   - EllipseFit.kt implements Fitzgibbon/Pilu/Fisher (1999) in the
        //     numerically stable Halir-Flusser form. Fits all five ellipse
        //     parameters, where the pooled estimate in HoughCentre fits two
        //     and assumes three. Verified against an independent
        //     implementation: axis ratio and orientation agree to five
        //     decimal places on twelve reference cases.
        //   - RingShapeSelector picks between circle and ellipse by CROSS
        //     VALIDATION — fit both on 70% of the outline, score both on the
        //     untouched 30%. An ellipse always wins on the points it was
        //     fitted to, so residuals alone would pick it even on a perfectly
        //     square-on target, where a spurious 3% correction is about half
        //     a ring at the outer edge.
        //   - MarkOutline extracts the aiming mark as a connected component
        //     and checks it has not leaked along a ring line through a bullet
        //     hole; two ray-casting extractors were tried first and both
        //     invented ellipticity that was not there.
        //   - ShapeCorrection de-foreshortens the frame BEFORE the ring pitch
        //     is measured, so the existing radial fit gets an image its
        //     circular assumption holds for.
        //
        //   Measured, on real targets warped by angles chosen in advance —
        //   mean absolute score error per shot, ISSF 10 m air rifle face:
        //     tilt      circle   ellipse
        //     10 deg     0.092     0.083
        //     20 deg     0.201     0.154
        //     30 deg     0.366     0.216
        //     40 deg     0.565     0.282
        //   So it recovers roughly half of what a circle discards at 30-40
        //   degrees. A full ring-family homography would recover about a
        //   further quarter and is NOT implemented, because it needs the true
        //   ring radii and would therefore turn a wrong face identification
        //   into a confident wrong score. This needs no face knowledge.
        //
        //   KNOWN OPEN DEFECT, surfaced by this work and NOT caused by it:
        //   the ring-pitch ladder in RingFinder is unstable on oblique
        //   photographs, returning 10.4, 36.1 and 7.6 px for the same target
        //   at three tilts WITHOUT any correction applied. The uncorrected
        //   fit is now logged alongside the corrected one so the two can be
        //   compared from a shared field log. Scale should not be trusted on
        //   an angled photograph until that is fixed.
        //
        // 1.9.1 — correction: four compile errors from the 1.6.0-1.9.0 run of
        //         releases, none of which had been through a compiler.
        //   - SessionActivity had TWO companion objects, which made
        //     IDENTIFY_TOLERANCE unresolvable and reported the error on a
        //     different line than the cause.
        //   - ProfileActivity kept a return@onSelected after the function it
        //     labelled was renamed to onSelectedIndex.
        //   - the ported VTB ScopeCatalog's `when` covers VTB's three click
        //     units; this app has six, because match diopters are quoted in
        //     millimetres at a distance rather than as an angle.
        //   tools/kotlin_checks.py now catches all three, and CI runs it
        //   BEFORE the compiler, since it names the file and line where the
        //   compiler reports consequences elsewhere.
        // 1.9.0 — the two gaps from 1.8.0, and Hough centre voting.
        //   - the target spinners on the detection screens now show a picture
        //     of each face. 1.8.0 put thumbnails only in the Targets tab,
        //     which is where a face is browsed; the spinner is where one is
        //     CHOSEN, moments before scoring against it, and that is the half
        //     that mattered.
        //   - live camera detection uses the colour channel too, computed in
        //     YUV from the frame's own median. 1.8.0 left it on luminance,
        //     which was a defensible decision taken silently.
        //   - HoughCentre: every edge on a ring has a normal pointing at the
        //     common centre, so each votes along it. Measured on four real
        //     targets, within 2-4 px every time, and unlike a symmetry search
        //     it survives a thumb over a corner or a target filling part of
        //     the frame. It now seeds RingFinder.
        //
        //   HONEST LIMIT, since Hough was asked for to handle ANGLED targets
        //   and does not by itself: under perspective a ring projects to an
        //   ELLIPSE, which a circle accumulator has no parameter for, and a
        //   five-dimensional ellipse Hough is not a phone computation. What is
        //   affordable is measuring the ellipticity, pooled across every ring
        //   — and on four real targets that sits at a ~4 degree noise floor:
        //   3.1 and 3.9 degrees on targets that are perfect circles, against
        //   5.1 on a genuinely angled photograph. So it is reported and it
        //   seeds the tilt sliders above 8 degrees, and it is never applied
        //   on its own.
        // 1.8.0 — five features, and the first two change how registration
        //         works rather than tuning it.
        //   COLOUR. Hole detection reads distance from THE PAPER'S OWN COLOUR
        //     rather than brightness. A pellet hole is brown, not merely dark:
        //     on a real card that doubles its separation from the paper, 115
        //     luma levels against 242. Measuring the paper rather than
        //     assuming it is neutral matters — an ISSF card is yellow, and
        //     the obvious "brightness minus chroma" channel sends its paper to
        //     black and every hole with it.
        //   RING FITTING. The scale now comes from the PRINTED RING SPACING,
        //     fitted across the whole family and refined by least squares,
        //     instead of from the aiming mark times a ratio taken from
        //     whichever face was selected in a menu. Measured on four real
        //     targets: pitch to within 0.0-1.5%, where the aiming-mark ratio
        //     was out by 6%. The fit also IDENTIFIES the face — the right one
        //     agreed to 0.3-1.3% while the runner-up was 8% or worse — which
        //     removes the wrong-face failure at its source.
        //   Editing: shots can be added, deleted and DRAGGED, and are
        //     rescored where they are dropped.
        //   Catalogues: every VTB rifle, load and sight ported verbatim, with
        //     VTB's filter dialogs, plus the match sights and pistols STS
        //     needs. 41 firearms, 68 loads, 51 sights.
        //   Thumbnails: a drawing of each face beside its name in the picker.
        // 1.7.1 — three corrections, two of which had one cause.
        //   - the black text was NOT the spinners (1.7.0 fixed those); it was
        //     all 35 BORDERLESS BUTTONS. "?android:attr/borderlessButtonStyle"
        //     takes the platform style, whose text colour comes from platform
        //     attributes this app cannot reach. Replaced with Sts.TextButton,
        //     which sets textColor on the view's own style.
        //   - a Spinner delivers its first onItemSelected on the layout pass
        //     AFTER onCreate, i.e. after the listener is attached. Opening
        //     Session or Import therefore ran the rules listener, which forces
        //     the target face to the rule set's default — silently reverting a
        //     chosen target to ISSF 10 m Air Rifle every time. That is why the
        //     Results screen kept naming that face AND why detection kept
        //     finding nothing: it was running against the wrong geometry. The
        //     initial selection is now recognised and ignored.
        //   - an EMPTY session now follows the current target and rules.
        //     A scored session still keeps what it was scored with.
        // 1.7.0 — feature: clear the log (the button existed but four controls
        //         in one row pushed it off the right-hand edge of a phone, so
        //         the toolbar is now two rows); clear the recorded shots,
        //         from Results, Import and Session, keeping the target, rules
        //         and distance so the same card can be re-scored.
        //       — correction: EVERY SPINNER IN THE APP rendered black text on
        //         the dark background. android.R.layout.simple_spinner_item
        //         applies textAppearanceMediumInverse — coloured for the
        //         inverse of the theme — and no app style can override it
        //         because the colour comes from the platform text appearance,
        //         not from textColor. Replaced with our own item layouts.
        //       — correction: a photograph that detected nothing left the
        //         previous session untouched, so Results went on showing an
        //         older target's shots as though they were the new ones. The
        //         session is now replaced BEFORE detection runs.
        //       — correction: TargetGeometryCheck.verifyRings catches the
        //         other half of the wrong-face problem. The outer-radius test
        //         catches a box that is too small; this one catches a box in
        //         exactly the right place on a face whose RING SPACING does
        //         not match the card, which scores everything wrongly while
        //         looking entirely normal.
        // 1.6.0 — feature: a diagnostic log reachable from the Home screen,
        //         hideable in Settings, with a Report button that shares the
        //         log TOGETHER with the active face, rules, gauge and
        //         equipment. Detection now logs its own parameters and every
        //         candidate it accepted or rejected.
        //       — correction: the app no longer stays silent when the selected
        //         target face does not match the target in the picture.
        //         TargetGeometryCheck looks for printed rings outside the
        //         proposed registration box; if they are there, the box is too
        //         small and the wrong face is selected. Photographing an
        //         air-PISTOL card with the air RIFLE face chosen put the box
        //         on the FIVE ring (188 px against the ring's 194 px), halved
        //         every distance, scored the centre shot a 10 and every other
        //         shot a miss — a complete, confident, wrong score sheet from
        //         a wrong menu selection.
        //       — correction: the scoring-area limit went from 1.02x to 1.10x
        //         the outer ring, so a shot just outside the last ring is
        //         reported as the miss it is. Card furniture sits much further
        //         out; the logo that prompted the limit was at 1.21x.
        // 1.5.3 — corrections from running the detector against two real
        //         uploaded targets rather than synthetic data:
        //   - printed ring NUMERALS were being scored as shots. Radial
        //     normalisation removes rings but not numerals, which occupy four
        //     angles out of 360 and barely move a radial median. A synthetic
        //     face returned 22 candidates for 5 real shots, 17 of them digits.
        //     Now rejected by testing each candidate for rotational twins.
        //     A four-fold median over the whole image was tried first and
        //     LOST two of five real shots on a photograph — see the note in
        //     HoleDetector for why the per-candidate form survives real
        //     lighting and registration error where the global one does not.
        //   - absolute detection now looks only inside the outermost ring.
        //     A club logo on a real target was reported as a shot, and the
        //     card furniture was raising the noise floor: excluding it
        //     dropped the robust sigma from 4.4 to 3.0 on that photograph,
        //     which was enough to find a faint fifth hole that had been
        //     missed.
        // 1.5.2 — corrections for three failures found in the field:
        //   (1) auto-detect skewed the box on square-on targets. It was
        //       APPLYING a tilt inferred from how elliptical the aiming mark
        //       measured, and a shot-up mark measures a few percent
        //       elliptical from segmentation noise alone — which acos turns
        //       into 14 to 25 degrees. The estimate is now offered on a
        //       button, never imposed. Mark detection also gained a
        //       centrality weighting and a central-crop retry, so a dark
        //       bench behind a white card no longer wins the histogram.
        //   (2) ring numerals never appeared in the targets database: the
        //       gate demanded ~60 px of annulus against the 33 px a 230 dp
        //       preview actually has, on every face at every density. The
        //       glyph now scales to the annulus instead.
        //   (3) hit detection bore no resemblance to reality. Two causes:
        //       out-of-frame pixels were averaged into the contrast windows,
        //       inventing holes around the rim wherever the photo did not
        //       cover the whole card; and nothing suppressed the printed
        //       rings, which carry FOUR TIMES the contrast of a real hole.
        //       Fixed by a validity-masked integral image and by subtracting
        //       the radial median, which removes everything rotationally
        //       symmetric — every ring, exactly — and leaves the holes.
        // 1.5.1 — correction: the first real CI run compiled everything and
        //         passed 75 of 76 tests. The failure was the TEST, not the
        //         app: it asserted that a 10 degree tilt is recovered, but
        //         suggestedTransform deliberately ignores anything under 11.4
        //         degrees. That gate is now the named, documented constant
        //         MIN_ELLIPTICITY_TO_SUGGEST instead of a bare 1.02, and the
        //         test asserts the behaviour that exists.
        // 1.5.0 — feature: individual TILT and ROTATION controls on the
        //         registration box, laid out the way a phone camera app lays
        //         them out. Box plus these is seven degrees of freedom, which
        //         is every one a flat target through a normal lens needs; the
        //         eighth, shear, cannot arise with square pixels. The tilt is
        //         seeded from the ellipticity of the detected aiming mark.
        // 1.4.0 — feature: registration by a square bounding box with
        //         draggable top-left and bottom-right handles, placed
        //         automatically from the detected black aiming mark and
        //         expanded to the whole scoring area by the face's own
        //         published ratio. Corner registration stays available for
        //         angled views, and the detector says when it is needed.
        //       — feature: ring values printed on the target plot at all four
        //         cardinal points, so the targets database shows scores on
        //         the circles.
        // 1.3.1 — correction: the photo-upload scoring entry points existed
        //         since 1.2.0 but were styled borderless — flat text under a
        //         filled button — and users could not find them. Both are now
        //         ordinary buttons, the Home one comes FIRST, and both read
        //         "Upload a target photo to score".
        // 1.3.0 — feature: Play Store listing material — short and full
        //         descriptions and the 1024x500 feature graphic, in play/.
        //         The graphic is generated from the SAME drawing as the icon
        //         (tools/generate_feature_graphic.py imports the icon's
        //         artwork), so the listing and the home screen cannot drift.
        // 1.2.1 — correction: applicationId is now com.STS, and CI builds
        //         release artefacts only (no debug APK). Signing degrades to
        //         unsigned rather than failing when no keystore is present.
        // 1.2.0 — feature: score a target from a photograph after the session
        //         (ImportActivity), and a shot distribution histogram shown on
        //         Results, live on Session, on the import screen and in the
        //         shared report.
        //       — correction found while writing its tests: ImageLoader's
        //         sample size used the common idiom that stops one step early
        //         and returns an image LARGER than the requested cap, so a
        //         50 MP photo decoded at 4080 px instead of 3000.
        // 1.1.0 — feature: real launcher and Play Store icon, generated from
        //         one drawing (tools/generate_icons.py) into the 512 px store
        //         asset, the adaptive foreground at five densities, and the
        //         Android 13+ monochrome layer.
        // 1.0.1 — correction: removed res/mipmap-hdpi/README.txt, which the
        //         resource merger rejects (res accepts only .xml and .png).
        versionCode = 76
        versionName = "1.52.1"
    }

    // Resolved once, here, rather than re-read from the environment in two
    // places. CI sets these variables unconditionally, so an absent secret
    // arrives as an EMPTY STRING and not as null — and file("") resolves to
    // the project directory, which would be accepted as a keystore path and
    // then fail deep inside the signing task with an unhelpful message. Blank
    // and missing-on-disk are both treated as "no keystore".
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_PATH")
        ?.takeIf { it.isNotBlank() }
        ?.let { file(it) }
        ?.takeIf { it.exists() }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Kept for local work in Android Studio. CI never builds it: the
        // workflow runs the unit tests against the RELEASE variant and
        // assembles only the release APK and the Play bundle, so nothing that
        // leaves the build machine is ever a debug artefact.
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 ON per Play's optimisation recommendation: it strips
            // unreachable library code and emits the mapping file Play asks
            // for (AGP packages that into the bundle automatically).
            //
            // SAFETY: proguard-rules.pro keeps ALL com.rfsat.sts classes and
            // members. Every persisted format in this app is Gson reflection
            // over field NAMES, so renaming a field silently changes a stored
            // JSON key — no crash, no build error, just vanished profiles,
            // targets and sessions.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // CameraX ships native code (libimage_processing_util_jni.so).
            // This packages its symbol table into the AAB metadata, where
            // Play picks it up; metadata only, not shipped to devices.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            // Unsigned when no keystore is configured: the build still
            // succeeds and still produces both artefacts, which is what makes
            // the workflow useful on a fork or a pull request. An unsigned
            // bundle cannot be uploaded to Play, and the workflow says so
            // rather than leaving it to be discovered at upload time.
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources.excludes.add("META-INF/*")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX — live target observation and still capture
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Profile / target / session persistence
    implementation("com.google.code.gson:gson:2.11.0")

    // Encrypted storage for the Claude API key. A key that bills the user's
    // own account does not belong in plain SharedPreferences, where any
    // process with root or a backup extraction can read it.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // RTSP. The platform MediaPlayer cannot do RTSP over TCP and cannot
    // decode H.265, which between them are why a camera that plays in VLC
    // showed nothing here. Two modules only — no UI, no session, no
    // extractors beyond what the RTSP source needs.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")

    testImplementation("junit:junit:4.13.2")
}
