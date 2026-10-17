`detailed-member-stop.csv` is based on a real Toggl Track Detailed Report supplied
for #319. Names, email, project, tags and description are replaced. Dates shift by
364 days and clocks by 17 minutes; interval durations and ordering remain exact.
Both periods use the same Europe/Oslo offset. The original file is unchanged.
The fixture retains the UTF-8 BOM, CRLF, Member/Stop headers, seven rows, short
entries, and absent Client/Task columns. CSV quoting is normalized to quote all
fields. Other parser cases are explicitly synthetic.

Toggl documents [Detailed Report exports](https://support.toggl.com/en-us/article/detailed-report-k9bzy2/)
and [Track export prerequisites](https://support.toggl.com/en-us/article/exporting-data-z53uki/).
The User/End variant in unit tests is a synthetic compatibility case; do not
describe it as another captured real export.
