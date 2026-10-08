"""PyInstaller entry point."""
import os
import sys

if getattr(sys, "frozen", False):
    # PyInstaller points LD_LIBRARY_PATH at the bundle. Only this process needs that
    # (and it's already loaded), and it breaks programs we start, like xdg-open for
    # links, so give them the user's original environment.
    orig = os.environ.pop("LD_LIBRARY_PATH_ORIG", None)
    if orig is not None:
        os.environ["LD_LIBRARY_PATH"] = orig
    else:
        os.environ.pop("LD_LIBRARY_PATH", None)
else:
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from jace_social_app.app import main  # noqa: E402

main()
