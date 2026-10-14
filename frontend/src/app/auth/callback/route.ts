import { NextResponse, type NextRequest } from "next/server";
import { SIGN_IN_COOKIE, appUrl, requestTokens, setTokenCookies, type SignIn, type Tokens } from "@/lib/oidc";

// Where the OIDC provider returns after sign-in: swaps the code for tokens and
// keeps them in httpOnly cookies for the /api proxy
export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  const error = params.get("error");
  if (error) {
    return new Response(`Sign-in failed: ${params.get("error_description") ?? error}`, { status: 400 });
  }

  let signIn: SignIn;
  try {
    signIn = JSON.parse(request.cookies.get(SIGN_IN_COOKIE)?.value ?? "");
  } catch {
    return new Response("Sign-in expired; please try again", { status: 400 });
  }
  const code = params.get("code");
  if (!code || params.get("state") !== signIn.state) {
    return new Response("Sign-in failed: invalid state", { status: 400 });
  }

  let tokens: Tokens;
  try {
    tokens = await requestTokens({
      grant_type: "authorization_code",
      code,
      redirect_uri: `${appUrl(request)}/auth/callback`,
      code_verifier: signIn.verifier,
    });
  } catch {
    return new Response("Sign-in failed: the OIDC provider did not issue a token", { status: 502 });
  }

  const response = NextResponse.redirect(new URL(signIn.returnTo, appUrl(request)));
  setTokenCookies(response, request, tokens);
  response.cookies.delete(SIGN_IN_COOKIE);
  return response;
}
