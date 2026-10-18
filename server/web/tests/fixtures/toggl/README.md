`detailed-member-stop.csv` is based on a real Toggl Track Detailed Report supplied
for #319. Names, email, project, tags and description are replaced. Dates shift by
364 days and clocks by 17 minutes; interval durations and ordering remain exact.
Both periods use the same Europe/Oslo offset. The original file is unchanged.
The fixture retains the UTF-8 BOM, CRLF, Member/Stop headers, seven rows, short
entries, and absent Client/Task columns. CSV quoting is normalized to quote all
fields. Other parser cases are explicitly synthetic.

Toggl documents [Detailed Report exports](https://support.toggl.com/en-us/article/detailed-report-k9bzy2/)
and [Track export prerequisites](https://support.toggl.com/en-us/article/exporting-data-z53uki/).
`legacy-user-end.csv` is based on a real Toggl Track Legacy export supplied on
2026-10-18. User, email, client and project names are replaced. Dates shift by
364 days and clocks by 17 minutes, retaining the same Europe/Oslo offset,
seven intervals and exact total of 42:04:22. Empty Task/Description/Tags fields,
the Billable column, User/End headers, BOM and CRLF are retained; quoting is
normalized to quote all fields. The original file is unchanged. Other User/End
cases in unit tests are synthetic.
