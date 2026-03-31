import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "KosherEats - Kosher Food Delivery",
  description: "Order kosher food from the best restaurants near you",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>
        <div className="min-h-screen flex flex-col">
          {children}
        </div>
      </body>
    </html>
  );
}
