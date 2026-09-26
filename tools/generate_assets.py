#!/usr/bin/env python3
"""
generate_assets.py — generates the transparent contour-line PNGs used as
assets by the live wallpaper.

    pip install numpy matplotlib
    python generate_assets.py

Output (./assets folder):
    lines_cover.png      1080x2520  all contours, single layer
    lines1_cover.png     low altitude band
    lines2_cover.png     middle band
    lines3_cover.png     high band
    lines_main.png       1968x2184  same, for the foldable's inner screen
    lines1_main.png ... lines3_main.png

The contours are drawn in WHITE on a transparent background: in the app, a
PorterDuffColorFilter(color, SRC_IN) swaps the colour while keeping the alpha channel.

Both sets come from the SAME terrain field at the same scale — the outer screen's
crop is the central strip of the inner one, so when the device is unfolded the
map continues instead of switching to a different image.
"""

import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

SEED = 42          # same seed = same terrain, always
OCTAVES = 5        # 2 = broad, organic shapes; 6+ = detailed terrain
LEVELS = 34        # number of contours
GRID_H = 700       # field sampling resolution
COVER = (1080, 2520)
MAIN = (1968, 2184)


def fractal_noise(h, w, octaves, persistence=0.55, rng=None):
    """Smooth fractal noise via low-pass filtering in the frequency domain."""
    rng = rng or np.random.default_rng()
    field = np.zeros((h, w))
    amplitude, total = 1.0, 0.0

    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    radius = np.sqrt(fy ** 2 + fx ** 2)

    for i in range(octaves):
        cutoff = 0.004 * (2 ** i)
        spectrum = np.fft.fft2(rng.standard_normal((h, w)))
        spectrum *= np.exp(-(radius / cutoff) ** 2)
        layer = np.real(np.fft.ifft2(spectrum))
        std = layer.std()
        if std > 0:
            layer /= std
        field += amplitude * layer
        total += amplitude
        amplitude *= persistence

    field /= total
    field -= field.min()
    return field / field.max()


def add_flow(field, rng, strength=0.35):
    """Skews the field with a diagonal gradient, avoiding isolated blobs."""
    h, w = field.shape
    yy, xx = np.mgrid[0:h, 0:w]
    angle = rng.uniform(0, np.pi)
    ramp = np.cos(angle) * xx / w + np.sin(angle) * yy / h
    return (1 - strength) * field + strength * ramp


def build_field(width, height, seed, octaves, grid_h=GRID_H):
    rng = np.random.default_rng(seed)
    grid_w = max(2, int(round(grid_h * width / height)))
    return add_flow(fractal_noise(grid_h, grid_w, octaves, rng=rng), rng)


def center_strip(field):
    """Crops the strip with the outer screen's aspect ratio from the inner-screen field."""
    frac = (COVER[0] / COVER[1]) / (MAIN[0] / MAIN[1])
    grid_h, grid_w = field.shape
    strip = max(2, int(round(grid_w * frac)))
    x0 = (grid_w - strip) // 2
    return field[:, x0:x0 + strip]


def contour_levels(field, levels=LEVELS):
    """Altitude levels. Computed ONCE, on the master field, and reused for both
    sets — if each crop computed its own, the outer screen's contours would land at
    different altitudes from the inner screen's and the map would jump when unfolding."""
    return np.linspace(field.min(), field.max(), levels + 2)[1:-1]


def export_layers(field, outdir, width, height, tag, levels, bands=3,
                  glow=True, dpi=100):
    grid_h, grid_w = field.shape
    xs = np.linspace(0, grid_w, grid_w)
    ys = np.linspace(0, grid_h, grid_h)
    all_levels = np.asarray(levels)

    groups = {f"lines_{tag}.png": (all_levels, 0)}
    for i, idx in enumerate(np.array_split(np.arange(len(all_levels)), bands), 1):
        groups[f"lines{i}_{tag}.png"] = (all_levels[idx], int(idx[0]))

    for fname, (lv, offset) in groups.items():
        if len(lv) == 0:
            continue
        fig = plt.figure(figsize=(width / dpi, height / dpi), dpi=dpi)
        fig.patch.set_alpha(0.0)
        ax = fig.add_axes([0, 0, 1, 1])
        ax.set_axis_off()
        ax.patch.set_alpha(0.0)

        if glow:                      # soft halo behind the lines
            ax.contour(xs, ys, field, levels=lv, colors="white",
                       linewidths=4.5, alpha=0.12)
        # alternating width: every 5th contour is thicker, as on a real map
        widths = [1.9 if (offset + i) % 5 == 0 else 0.85 for i in range(len(lv))]
        ax.contour(xs, ys, field, levels=lv, colors="white",
                   linewidths=widths, alpha=0.92)

        ax.set_xlim(0, grid_w)
        ax.set_ylim(grid_h, 0)
        path = os.path.join(outdir, fname)
        fig.savefig(path, dpi=dpi, pad_inches=0, transparent=True)
        plt.close(fig)
        print(path)


def main():
    outdir = "assets"
    os.makedirs(outdir, exist_ok=True)
    master = build_field(*MAIN, SEED, OCTAVES)
    levels = contour_levels(master)
    export_layers(master, outdir, *MAIN, "main", levels)
    export_layers(center_strip(master), outdir, *COVER, "cover", levels)


if __name__ == "__main__":
    main()
