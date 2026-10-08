import SocialApp from "@/components/SocialApp";

export const metadata = { title: "Join a server - Jace Social" };

export default async function InvitePage({ params }: { params: Promise<{ code: string }> }) {
  return <SocialApp inviteCode={(await params).code} />;
}
