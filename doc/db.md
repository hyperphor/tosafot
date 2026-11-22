# Tosafot DB

Tosafot annotations live in an IndexedDB databse. The following command will copy it to a sqlite db and open a repl to it:

    bb db

The database lives in `out/tosafot.db`. You can query it directly:

    sqlite3 out/tosafot.db "select obj from objects order by updated desc" | jq .
	
	
To get annotations for a particular page:

	sqlite3 out/tosafot.db "select obj from objects where json_extract(obj, '\$.\"annotation/url\"') = 'https://www.astralcodexten.com/p/your-book-review-orality-and-literacy'" | jq .
	
	
To see all the json fields

	select distinct key from objects, json_each(objects.obj);

