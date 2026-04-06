/** @type {import('next').NextConfig} */
const nextConfig = {
  // Use 'standalone' for Docker/Fly only. Vercel handles output natively.
  ...(process.env.VERCEL ? {} : { output: 'standalone' }),
  images: {
    // Unsplash is used for restaurant + menu item photos in dev.
    // Add the prod CDN host here when cutover happens.
    remotePatterns: [
      { protocol: 'https', hostname: 'images.unsplash.com' },
      { protocol: 'https', hostname: '**.s3.amazonaws.com' },
      { protocol: 'https', hostname: '**.cloudfront.net' },
    ],
  },
};

module.exports = nextConfig;
