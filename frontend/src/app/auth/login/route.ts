import { NextResponse, type NextRequest } from "next/server";
import {
  SIGN_IN_COOKIE,
  appUrl,
  codeChallenge,
  config,
  cookieOptions,
  discover,
  randomToken,
  safeReturnTo,
  type SignIn,
} from "@/lib/oidc";

// Sends the user to the OIDC provider's sign-in page
export async function GET(request: NextRequest) {
  let authorizationEndpoint: string;
  try {
    authorizationEndpoint = (await discover()).authorization_endpoint;
  } catch {
    return new Response(`Sign-in unavailable: no OIDC provider at ${config.issuer}`, { status: 502 });
  }

  const signIn: SignIn = {
    state: randomToken(),
    verifier: randomToken(),
    returnTo: safeReturnTo(request.nextUrl.searchParams.get("returnTo")),
  };
  const url = new URL(authorizationEndpoint);
  url.search = new URLSearchParams({
    response_type: "code",
    client_id: config.clientId,
    redirect_uri: `${appUrl(request)}/auth/callback`,
    scope: config.scope,
    state: signIn.state,
    code_challenge: codeChallenge(signIn.verifier),
    code_challenge_method: "S256",
  }).toString();

  const response = NextResponse.redirect(url);
  response.cookies.set(SIGN_IN_COOKIE, JSON.stringify(signIn), cookieOptions(request, 600));
  return response;
}
