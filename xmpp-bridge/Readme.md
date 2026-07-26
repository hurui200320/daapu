# xmpp-bridge

This is a python sidecar for the main bot, mostly for bridging XMPP with OMEMO.

Although I don't like python, the Java's Smack library is too buggy to work with.
My goal is to build chatbot, not create another XMPP library in Java/Kotlin.
So I have no choice but to use Python.

This project use `uv`.

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

To access an RFC: fetch url `https://www.rfc-editor.org/rfc/rfc1234.txt` (replace 1234 with real RFC number).
To access an XEP: fetch url `https://xmpp.org/extensions/xep-1234.html` (replace 1234 with real XEP number).
