/** @type {import('next').NextConfig} */
const nextConfig = {
  // Use 'standalone' for Docker/Fly only. Vercel handles output natively.
  ...(process.env.VERCEL ? {} : { output: 'standalone' }),
  images: {
    // Restaurant/menu photos come from external CDNs (R2, Unsplash). Skip the
    // self-hosted Next image optimizer (it 400s on these hosts under standalone)
    // and let the browser load them directly — they're already web-sized.
    unoptimized: true,
    // Unsplash is used for restaurant + menu item photos in dev.
    // Add the prod CDN host here when cutover happens.
    remotePatterns: [
      { protocol: 'https', hostname: 'images.unsplash.com' },
      { protocol: 'https', hostname: '**.s3.amazonaws.com' },
      { protocol: 'https', hostname: '**.cloudfront.net' },
      // Cloudflare R2 — where seller-uploaded + imported restaurant/menu
      // photos live (public bucket pub-*.r2.dev). Without this, Next/Image
      // blocks every cover and the cards render blank.
      { protocol: 'https', hostname: '**.r2.dev' },
      { protocol: 'https', hostname: '**.r2.cloudflarestorage.com' },
    ],
  },
};

module.exports = nextConfig;
