import { NextResponse, type NextRequest } from "next/server";
import { ID_TOKEN_COOKIE, appUrl, clearTokenCookies, config, discover } from "@/lib/oidc";

// Forgets the tokens, then ends the provider's session too when it
// supports RP-initiated logout (Keycloak does; Cognito doesn't advertise it)
export async function GET(request: NextRequest) {
  const home = `${appUrl(request)}/profile`;
  let target = new URL(home);
  try {
    const endSession = (await discover()).end_session_endpoint;
    if (endSession) {
      target = new URL(endSession);
      const params = new URLSearchParams({ client_id: config.clientId, post_logout_redirect_uri: home });
      // Without it Keycloak asks "Do you want to log out?" before coming back
      const idToken = request.cookies.get(ID_TOKEN_COOKIE)?.value;
      if (idToken) params.set("id_token_hint", idToken);
      target.search = params.toString();
    }
  } catch {
    // provider unreachable: signing out of this app is still done
  }

  const response = NextResponse.redirect(target);
  clearTokenCookies(response);
  return response;
}
