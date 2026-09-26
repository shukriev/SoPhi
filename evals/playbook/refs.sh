# Reference solutions, used ONLY by selftest.sh to prove each check can pass. Never shown to Sophi.

ref_tool_1() { echo parse_amount > answer.txt; }
ref_tool_2() { grep -rl total_by_category --include='*.py' . | xargs perl -pi -e 's/\btotal_by_category\b/totals_by_category/g'; }
ref_tool_3() { echo 7 > answer.txt; }
ref_tool_4() { echo "Key words for use in RFCs to Indicate Requirement Levels" > answer.txt; }
