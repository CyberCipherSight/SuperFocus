# FocusBro launcher icon

The active icon is `focusbro-energy-icon.png`; see [ENERGY-ICON.md](ENERGY-ICON.md) for its prompt and integration.

`focusbro-icon-final.png` is the superseded leaf/timer proposal. `focusbro-icon.png` is the initial transparent draft, retained as a design source only.

Created with the built-in image generation tool. Android uses density-specific `ic_focusbro.png` assets and adaptive icon XML; Android 13+ also has a native monochrome mark for themed launchers. Existing launcher resources were left intact.

## Generation prompt

Use case: logo-brand. Create a polished Android launcher icon for FocusBro, a calm productivity and scheduled focus app. One square 1024x1024 production asset, full bleed deep forest teal background (#164E43), with a bold warm ivory abstract focus mark: an elegant circular timer ring, a deliberate small break at upper right, and a simple leaf-shaped pointer pointing upward inside, suggesting focused time and personal growth. Distinctive, balanced, exceptionally clear at 48 pixels. Flat vector-like graphic with clean smooth edges, restrained premium look matching a sage/teal productivity UI. Center the entire ivory mark within the central 58 percent of the canvas so circular and squircle launcher crops never clip it. Background fills the entire square, no rounded outer tile corners. No text, letters, wordmark, device mockup, shadows, border, photo, or extra objects. Output the icon alone.

## Refinement prompt

Finalize this exact FocusBro leaf-and-timer icon as a production Android launcher image. Preserve the silhouette and centered layout of the ivory timer ring and upright leaf. Place it on a completely opaque uniform deep forest teal #164E43 background filling every pixel of the square. Remove all stray flecks, edge noise, texture, gradients, and sketch lines. Mark must be perfectly smooth, flat solid warm ivory #FFF4DC with clean antialiased edges. NO transparency anywhere: opaque teal both outside and inside the timer ring and in the leaf's central cutout. All mark shapes should fit inside a centered circle with diameter 60 percent of the square. No text, no mockup, no rounded outer corners, no border. Square output.

Resampling with ImageMagick creates the Android density exports; the adaptive foreground is 432 pixels (108 dp at xxxhdpi).
