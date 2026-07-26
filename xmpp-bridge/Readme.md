# xmpp-bridge

This is a python sidecar for the main bot, mostly for bridging XMPP with OMEMO.

Although I don't like python, the Java's Smack library is too buggy to work with. My goal is to
build chatbot, not create another XMPP library in Java/Kotlin. So I have no choice but to use
Python.

This project use `uv`.

## Status

Currently **only 1:1 DM** is supported. MUC (group chat) support is planned for a later iteration.

Currently, the XMPP bridge does NOT have any HA cluster set up. Each account should be owned by one
and only one instance exclusively.

> Note on OMEMO protocol versions: slixmpp-omemo 2.2.0 fully supports
> decrypting `eu.siacs.conversations.axolotl` (a.k.a. oldmemo / legacy
> OMEMO) DMs. The newer `omemo:2` (twomemo) DM path raises
> `NotImplementedError` for SCE unpacking in this version, so most clients
> should fall back to oldmemo transparently when talking to this bot.

## Configuration

The bridge reads its configuration from environment variables (in development, place these in a
`.env` file alongside the main project; under Docker Compose they are provided via `env_file`):

| Variable                | Required | Description                                                                                                                                                                    |
|-------------------------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `XMPP_SERVER_HOST`      | yes      | Hostname/IP to connect to (may differ from domain).                                                                                                                            |
| `XMPP_ACCOUNT_JID`      | yes      | Account jid.                                                                                                                                                                   |
| `XMPP_ACCOUNT_PASSWORD` | yes      | Account password.                                                                                                                                                              |
| `XMPP_OMEMO_STORE_DIR`  | no       | Directory for the OMEMO JSON store (default `./omemo-store`).                                                                                                                  |
| `XMPP_PROXY`            | no       | Full proxy URL to tunnel the XMPP connection through, e.g. `socks5://user:pass@host:port`. Accepted schemes: `socks5`, `socks5h`, `socks4`, `socks4a`, `http`. Off by default. |

## Coding style

+ Always add type annotation for local variables, method parameter and method return value

## References

For Coding agents.

### slixmpp

+ Code https://codeberg.org/poezio/slixmpp
+ Docs https://slixmpp.readthedocs.io/
+ PyPI https://pypi.org/project/slixmpp/

### slixmpp-omemo

+ Code https://github.com/syndace/slixmpp-omemo
+ Docs https://slixmpp-omemo.readthedocs.io/
+ PyPI https://pypi.org/project/slixmpp-omemo/

### NATS

+ nats-py https://github.com/nats-io/nats.py
  +Docs https://docs.nats.io

### XMPP

+ RFCs https://xmpp.org/rfcs/
+ XEPs https://xmpp.org/extensions/

To access an RFC: fetch url `https://www.rfc-editor.org/rfc/rfc1234.txt` (replace 1234 with real RFC
number). To access an XEP: fetch url `https://xmpp.org/extensions/xep-1234.html` (replace 1234 with
real XEP number).
