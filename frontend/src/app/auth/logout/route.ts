import { NextResponse, type NextRequest } from "next/server";
import { TOKEN_COOKIE, appUrl, config, discover } from "@/lib/oidc";

// Forgets the access token, then ends the provider's session too when it
// supports RP-initiated logout (Keycloak does; Cognito doesn't advertise it)
export async function GET(request: NextRequest) {
  const home = `${appUrl(request)}/profile`;
  let target = new URL(home);
  try {
    const endSession = (await discover()).end_session_endpoint;
    if (endSession) {
      target = new URL(endSession);
      target.search = new URLSearchParams({ client_id: config.clientId, post_logout_redirect_uri: home }).toString();
    }
  } catch {
    // provider unreachable: signing out of this app is still done
  }

  const response = NextResponse.redirect(target);
  response.cookies.delete(TOKEN_COOKIE);
  return response;
}
