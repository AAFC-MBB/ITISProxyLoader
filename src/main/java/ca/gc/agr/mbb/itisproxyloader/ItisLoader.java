package ca.gc.agr.mbb.itisproxyloader;

/* ItisLoader: reads from sqlite3 database export of ITIS 
   found here: http://www.itis.gov/downloads/itisSqlite.zip
   and writes a BDB cache, to be used by ITISProxy
   NB: you need to add the following indexes to the sqlite3 db for this
   software to operate not uber slow:
   --
   CREATE INDEX "jurisdiction_jurisdiction_index_tsn" ON "jurisdiction" ("tsn");
   CREATE INDEX "taxonomic_units_tsn_index" ON "taxonomic_units" ("tsn");
   CREATE INDEX "taxonomic_units_parent_tsn_index" ON "taxonomic_units" ("parent_tsn");
   CREATE INDEX "strippedauthor_author_id_index" ON "strippedauthor" ("taxon_author_id");
   CREATE INDEX "synonym_links_tsn_index" ON "synonym_links" ("tsn");
   CREATE INDEX "synonym_links_tsn_accepted_index" ON "synonym_links" ("tsn_accepted");
   CREATE INDEX "taxonomic_units_taxon_author_id_index" ON "taxonomic_units" ("taxon_author_id");
   --

   Requires installation of http://code.google.com/p/sqlite4java/
   These libraries (libsqlite_jni.la  libsqlite_jni.so in the version tested: sqlite4java-282.zip)
   need to be in the LD_LIBRARY_PATH and the JAR from this package, sqlite.jar 
   needs to be in the CLASSPATH
 */

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import ca.gc.agr.itis.itismodel.ItisRecord;
import ca.gc.agr.itis.itismodel.TaxonomicRank;
import ca.gc.agr.mbb.itisproxy.CachingProxyImpl;
import ca.gc.agr.mbb.itisproxy.ProxyImpl;
import ca.gc.agr.mbb.itisproxy.entities.AcceptedName;
import ca.gc.agr.mbb.itisproxy.entities.AcceptedNamesList;
import ca.gc.agr.mbb.itisproxy.entities.Comment;
import ca.gc.agr.mbb.itisproxy.entities.Comment;
import ca.gc.agr.mbb.itisproxy.entities.CommentList;
import ca.gc.agr.mbb.itisproxy.entities.CommonName;
import ca.gc.agr.mbb.itisproxy.entities.CommonNamesList;
import ca.gc.agr.mbb.itisproxy.entities.CommonNamesList;
import ca.gc.agr.mbb.itisproxy.entities.CompletenessRating;
import ca.gc.agr.mbb.itisproxy.entities.CredibilityRating;
import ca.gc.agr.mbb.itisproxy.entities.CurrencyRating;
import ca.gc.agr.mbb.itisproxy.entities.Expert;
import ca.gc.agr.mbb.itisproxy.entities.ExpertList;
import ca.gc.agr.mbb.itisproxy.entities.FullRecord;
import ca.gc.agr.mbb.itisproxy.entities.GeoDivision;
import ca.gc.agr.mbb.itisproxy.entities.GeoDivision;
import ca.gc.agr.mbb.itisproxy.entities.GeographicDivisionsList;
import ca.gc.agr.mbb.itisproxy.entities.GetFullHierarchyFromTSN;
import ca.gc.agr.mbb.itisproxy.entities.GetKingdomNames;
import ca.gc.agr.mbb.itisproxy.entities.HierarchyRecord;
import ca.gc.agr.mbb.itisproxy.entities.JurisdictionalOrigin;
import ca.gc.agr.mbb.itisproxy.entities.JurisdictionalOrigin;
import ca.gc.agr.mbb.itisproxy.entities.JurisdictionalOriginsList;
import ca.gc.agr.mbb.itisproxy.entities.Kingdom;
import ca.gc.agr.mbb.itisproxy.entities.OtherSource;
import ca.gc.agr.mbb.itisproxy.entities.OtherSourceList;
import ca.gc.agr.mbb.itisproxy.entities.ParentTsn;
import ca.gc.agr.mbb.itisproxy.entities.Publication;
import ca.gc.agr.mbb.itisproxy.entities.PublicationList;
import ca.gc.agr.mbb.itisproxy.entities.ScientificName;
import ca.gc.agr.mbb.itisproxy.entities.SearchByCommonName;
import ca.gc.agr.mbb.itisproxy.entities.SearchByScientificName;
import ca.gc.agr.mbb.itisproxy.entities.Synonym;
import ca.gc.agr.mbb.itisproxy.entities.SynonymList;
import ca.gc.agr.mbb.itisproxy.entities.TaxRank;
import ca.gc.agr.mbb.itisproxy.entities.TaxonAuthor;
import ca.gc.agr.mbb.itisproxy.entities.UnacceptReason;
import ca.gc.agr.mbb.itisproxy.entities.Usage;


public class ItisLoader
{
    static Connection connection = null;
    static final String DRIVER_NAME= "SQLite.JDBCDriver";

    static String url = "jdbc:sqlite:/";
    static String cacheDir = null;

    static final String DOC_TYPE_SRC = "SRC";
    static final String DOC_TYPE_PUB = "PUB";
    static final String DOC_TYPE_EXP = "EXP";

    public static final void usage(){
	System.err.println("\n\tUsage: java ca.gc.agr.mbb.itisproxyloader.ItisLoader absolute_location_of_ITIS_sqlite_db path_of_cache_dir\n");
	System.exit(42);
    }


    public static final void main(final String[] args) {
	if(args.length != 2){
	    System.err.println("\n\tERROR: Incorrect # of arguments\n");
	    usage();
	}

	String dbFileName = args[0];
	File dbFile = new File(dbFileName);
	if(!dbFile.exists() || !dbFile.canRead()){
	    System.err.println("\n\tERROR: Does not exist, or cannot read, db file: " + dbFileName);
	    usage();
	}

	url += dbFileName;
	cacheDir = args[1];
	try{
	    try {
		Class.forName(DRIVER_NAME).newInstance();
		connection = DriverManager.getConnection(url);
	    } catch(ClassNotFoundException e){
		System.err.println("Unable to find the JDBC driver: " + DRIVER_NAME);
		System.err.println("\t Try finding the driver jar and adding it to your CLASSPATH\n");
		System.err.println("\t See http://code.google.com/p/sqlite4java/ for info on building JAR and shared libraries");
		usage();
	    } catch(UnsatisfiedLinkError e){
		unsatisfiedLinkError();
		usage();
	    } 
	    catch(java.sql.SQLException e){
		if(e.getMessage().startsWith("java.lang.UnsatisfiedLinkError")){
		    unsatisfiedLinkError();
		}
		usage();
	    }
	    catch (Exception e) {
		e.printStackTrace();
		usage();
	    }
	    DatabaseMetaData md = connection.getMetaData();

	    ItisLoader itisLoader = new ItisLoader();

	    itisLoader.run();
	}
	catch(Throwable t){
	    t.printStackTrace();
	}
    }

    public static void unsatisfiedLinkError(){
	System.err.println("Unable to find the sqlite3 shared libraries");
	System.err.println("\t These need to be in your LD_LIBRARY_PATH or put the path in your java command: \"java - -Djava.library.path=...\"");
	System.err.println("\t See http://code.google.com/p/sqlite4java/ for info on building JAR and shared libraries");
    }

    public final void run(){
	// load the sqlite-JDBC driver using the current class loader
	System.out.println("ItisLoader: start run");
	try{
	    Class.forName(DRIVER_NAME).newInstance();
	}catch(ClassNotFoundException e){
	    e.printStackTrace();
	    return;
	}catch(InstantiationException e){
	    e.printStackTrace();
	    return;
	}
	catch(IllegalAccessException e){
	    e.printStackTrace();
	    return;
	}
	try
	    {
		// We have to do this in chunks because if we do it in all one select, the driver throws a OOM exception
		int chunkSize = 4000;
		int numRecords = getNumRecords();
		long numActualRecords = 0l;
		for(int i=0; i<numRecords; i+= chunkSize){
		    numActualRecords += getRecords(url, cacheDir, i, chunkSize);
		}
		System.out.println("****************Actual number of records added: " + numActualRecords);
	    }
	finally
	    {
		try
		    {
			if(connection != null)
			    connection.close();
		    }
		catch(SQLException e)
		    {
			// connection close failed.
			System.out.println(e);
		    }
	    }

    }

    int getNumRecords(){
	String count = "count";
	String sql = "select count(tsn) as " + count + " from taxonomic_units;";
	Statement statement = null;
	ResultSet rs = null;
	try{
	    statement = connection.createStatement();
	    rs = statement.executeQuery(sql);
	    rs.next();
	    return rs.getInt(count);
	}catch(Exception e){
	    e.printStackTrace();
	}finally{
	    closeAll(statement, rs);
	}
	return 0;
    }

    public static final Map<String, List<String>> getCommonNames(final Connection connection, final String tsn){
	Map<String, List<String>> cm = new HashMap<String, List<String>>();
	if(tsn == null){
	    return cm;
	}
	Statement statement = null;
	ResultSet rs = null;

	try{
	    statement = connection.createStatement();
	    rs = statement.executeQuery("select language, vernacular_name from vernaculars where tsn=" + tsn);
	    while(rs.next()){
		String lang = rs.getString("language");
		String vernacular = rs.getString("vernacular_name");
		List<String> verns = null;
		if(cm.containsKey(lang)){
		    verns = cm.get(lang);
		}else{
		    verns = new ArrayList<String>();
		    cm.put(lang, verns);
		}
		verns.add(vernacular);
	    }
	}catch(Exception e){
	    e.printStackTrace();
	}finally{
	    closeAll(statement, rs);
	}
	return cm;
    }

    public static List<TaxRank> getHierarchyAbove(final Connection connection, final String tsn )throws SQLException{
	List<TaxRank> listRank = new ArrayList<TaxRank>();
	if(tsn == null){
	    return listRank;
	}
	String sql = "select * from taxonomic_units where tsn=" + tsn;
	Statement statement = null;
	ResultSet rs = null;
	try{
	    statement = connection.createStatement();
	    rs = statement.executeQuery(sql);
	    while(rs.next()){
		String parentTsn = rs.getString("parent_tsn");
		String thisTsn = rs.getString("tsn");
		listRank.addAll(getHierarchyAbove(connection, parentTsn));
		
		TaxRank tr = new TaxRank();
		listRank.add(tr);
		tr.tsn = thisTsn;
		
		tr.commonNames = getCommonNames(connection, thisTsn);
		tr.rankId = rs.getString("rank_id");
		tr.rankName = makeRankName(connection, tr.rankId);
		tr.rankValue = rs.getString("complete_name");
		tr.kingdomId = rs.getString("kingdom_id");
		tr.kingdomName = getKingdom(connection, tr.kingdomId);
		
		/*
		  System.out.println("##  << tsn=" + tr.tsn 
		  + " rankId=" + tr.rankId 
		  + " rankName= " + tr.rankName 
		  + " rankValue= " + tr.rankValue
		  + " completeName= " + rs.getString("complete_name")
		  + "   kingdomId= " + tr.kingdomId
		  + "   kingdomName= " + tr.kingdomName);
		*/
		
	    }
	}
	finally{
	    closeAll(statement, rs);
	}
	return listRank;
    }

    static Map<String, String> idRankNameCache = new HashMap<String, String>();
    static final String makeRankName(final Connection connection, final String rankId){
	String value = (String)cacheLookup(idRankNameCache, rankId);
	if(value == null){
	    Statement statement = null;
	    ResultSet rs = null;
	    try{
		String sql = "select rank_name from taxon_unit_types where rank_id=" + rankId;
		statement = connection.createStatement();
		rs = statement.executeQuery(sql);
		rs.next();
		value = rs.getString("rank_name");
		idRankNameCache.put(rankId, value);
	    }
	    catch(Exception e){
		e.printStackTrace();
	    }
	    finally{
		closeAll(statement, rs);
	    }
	}
	return value;
    }


    static Map<String, String> idKingdomCache = new HashMap<String, String>();

    public static String getKingdom(final Connection connection, final String id){
	String value = (String)cacheLookup(idKingdomCache, id);
	if(value == null){
	    Statement statement = null;
	    ResultSet rs = null;
	    try{
		String sql = "select kingdom_name from kingdoms where kingdom_id=" + id;
		System.out.println("getKingdom: " + sql);
		statement = connection.createStatement();
		rs = statement.executeQuery(sql);
		if(!rs.isBeforeFirst()){
		    return null;
		}
		if(rs.next()){
		    System.out.println(rs.getString(1));
		    value = rs.getString(1);
		    idKingdomCache.put(id, value);
		}
	    }
	    catch(Exception e){
		e.printStackTrace();
	    }
	    finally{
		closeAll(statement, rs);
	    }
	}
	return value;
    }

    static Object cacheLookup(Map map, Object key){
	if(map.containsKey(key)){
	    return map.get(key);
	}
	return null;
    }


    public static List<TaxRank> getHierarchyOneLevelDown(final Connection connection, final String tsn)throws SQLException{
	List<TaxRank> belowRanks = new ArrayList<TaxRank>(15);
	if(tsn != null){
	    Statement statement = connection.createStatement();
	    String sql = "select * from taxonomic_units where parent_tsn=" + tsn;
	    System.out.println(sql);
	    ResultSet rs = statement.executeQuery(sql);
	    while(rs.next()){
		TaxRank tr = new TaxRank();
		tr.tsn = rs.getString("tsn");
		tr.rankId = rs.getString("rank_id");
		tr.commonNames = getCommonNames(connection, tr.tsn);
		tr.rankName = makeRankName(connection, tr.rankId);
		//tr.rankValue = rs.getString("unit_name1");
		tr.rankValue = rs.getString("complete_name");
		tr.kingdomId = rs.getString("kingdom_id");
		tr.kingdomName = getKingdom(connection, tr.kingdomId);
		belowRanks.add(tr);
		/*
		System.out.println("##    >>>   tsn=" + tr.tsn 
				   + " rankId=" + tr.rankId 
				   + " rankName= " + tr.rankName 
				   + " rankValue= " + tr.rankValue
				   + " completeName= " + rs.getString("complete_name")
				   + "   kingdomId= " + tr.kingdomId
				   + "   kingdomName= " + tr.kingdomName);
		*/
	    }
	    rs.close();
	    statement.close();
	}
	return belowRanks;
    }



    static long getRecords(final String url, final String cacheDir, final long start, final long end){
	System.out.println("getRecords: " + start + " " + end);

	Properties p = new Properties();
	p.setProperty(CachingProxyImpl.CACHE_LOCATION_KEY, cacheDir);
	p.setProperty(ProxyImpl.NO_CACHING_KEY, "true");
	p.setProperty(ProxyImpl.PROXY_IMPL_KEY, "caching");
	p.setProperty(ca.gnewton.tuapait.TCache.BDB_LOG_FILE_SIZE_MB_KEY, "128");

	CachingProxyImpl pi = new CachingProxyImpl((ProxyImpl)ProxyImpl.instance(p));
	pi.init(p);
	long numActualRecords = 0l;
	try
	    {
		//Connection connection = DriverManager.getConnection(url);
		String query = "select * from taxonomic_units  limit " + start + ", " + end;

		System.out.println("getRecords: "  + query);
		Statement statement = connection.createStatement();
		statement.setQueryTimeout(30);  // set timeout to 30 sec.
		ResultSet rs = statement.executeQuery(query);
		
		long now = System.currentTimeMillis();
		int n = 0;


		Connection connection2 = connection;
		while(rs.next())
		    {
			++numActualRecords;
			//connection2 = DriverManager.getConnection(url);
			++n;
			//System.out.println("\n==============================================");
			if(n %10 == 0){
			    System.out.print(".");
			}

			if(n %100 == 0){
			    
			    System.out.println(n + " " + ((double)(System.currentTimeMillis()-now))/1000.0);
			    now = System.currentTimeMillis();
			}
	
			FullRecord rec = new FullRecord();

			// read the result set
			String tsn = rs.getString("tsn");
			rec.tsn = tsn;
			

			rec.kingdom.kingdomId = rs.getString("kingdom_id");
			rec.kingdom.kingdomName = getKingdom(connection, rec.kingdom.kingdomId);
			Usage usage = new Usage();
			usage.taxonUsageRating = rs.getString("name_usage");
			rec.usage = usage;

			UnacceptReason unacceptReason = new UnacceptReason();
			unacceptReason.unacceptReason = rs.getString("unaccept_reason");
			rec.unacceptReason = unacceptReason;

			CurrencyRating currencyRating = new CurrencyRating();
			currencyRating.taxonCurrency = rs.getString("currency_rating");
			currencyRating.rankId = rs.getString("rank_id");
			rec.currencyRating = currencyRating;
			rec.completenessRating.completeness = rs.getString("completeness_rtng");
			rec.completenessRating.rankId = currencyRating.rankId;

			CredibilityRating credibilityRating = new CredibilityRating();
			credibilityRating.credRating = rs.getString("credibility_rtng");
			rec.credibilityRating = credibilityRating;
			
			rec.taxRank.kingdomId = rec.kingdom.kingdomId;
			rec.taxRank.kingdomName = rec.kingdom.kingdomName;
			rec.taxRank.rankId = rs.getString("rank_id");
			rec.taxRank.rankName = makeRankName(connection, rec.taxRank.rankId);

			String parentTsn = rs.getString("parent_tsn");
			rec.parentTsn.parentTsn = parentTsn;

			ScientificName sn = new ScientificName();
			rec.scientificName = sn;
			sn.combinedName = rs.getString("complete_name");
			sn.unitInd1 = rs.getString("unit_ind1");
			sn.unitInd2 = rs.getString("unit_ind2");
			sn.unitInd3 = rs.getString("unit_ind3");
			sn.unitInd4 = rs.getString("unit_ind4");
			sn.unitName1 = rs.getString("unit_name1");
			sn.unitName2 = rs.getString("unit_name2");
			sn.unitName3 = rs.getString("unit_name3");
			sn.unitName4 = rs.getString("unit_name4");

			sn.author = makeScientificNameAuthor(connection, tsn);

			String taxonAuthorId = rs.getString("taxon_author_id");
			
			rec.jurisdictionalOriginList = makeJurisdictionalOrigins(connection, tsn);;

			/////
			List<TaxRank> aboveRanks = getHierarchyAbove(connection2, parentTsn);

			List<TaxRank> belowRanks = getHierarchyOneLevelDown(connection2, tsn);
			
			//////////////
			rec.commentList = makeComments(connection, tsn);;
			//////////////

			rec.geographicDivisionList = makeGeographicDivisions(connection, tsn);

			//////////////
			rec.commonNameList = makeCommonNames(connection, tsn);

			//////////////
			rec.synonymList = makeSynonyms(connection, tsn);


			//////////////
			rec.acceptedNamesList = makeAcceptedNames(connection, tsn);

			//////////////

			makeRefs(rec, connection, tsn);

			//////////////

			rec.taxonAuthor = makeTaxonAuthor(connection, taxonAuthorId);

			/*
			System.out.println("---------------------------------------------------------------");
			System.out.println("QQQ : " + rec);
			System.out.println("_________________________________________________________________________");
			*/
	
			try{
			    ItisRecord ir = pi.populateFullItisRecord(rec, aboveRanks, belowRanks);
			    //printIR(ir);
			    pi.add(ir);
			}catch(Exception e){
			    e.printStackTrace();
			}
		    }
		//if(connection2 != null){
		//connection2.close();
		//}
		System.out.flush();

	    }
	catch(SQLException e)
	    {
		// if the error message is "out of memory", 
		// it probably means no database file is found
		System.err.println(e.getMessage());
		e.printStackTrace();
	    }
	return numActualRecords;
    }

    static final void printIR(ItisRecord ir){
	List<TaxonomicRank> taxonomicHierarchy = ir.getTaxonomicHierarchy();
	for(TaxonomicRank tr: taxonomicHierarchy){
	    System.out.println("QAZ: " + tr.getTsn() 
			       + ":" + tr.getRankName()
			       + ":" + tr.getRankValue()
			       + ":" + printCommonNames(tr.getCommonNames())
			       );
	}
    }

    static final String printCommonNames(Map<String, List<String>> commonNames){
	if(commonNames == null){
	    return "--no common names--";
	}
	StringBuilder sb = new StringBuilder();
	for (Map.Entry<String, List<String>> entry : commonNames.entrySet()) {
	    String lang = entry.getKey();
	    sb.append("[" + lang + ":");
	    List<String> names = entry.getValue();
	    for(String name: names){
		sb.append(name + "; ");
	    }
	    //System.out.println("key=" + entry.getKey() + ", value=" + entry.getValue());
		      sb.append("] ");
	}
	return sb.toString();
    }

    static final String makeScientificNameAuthor(final Connection connection, final String tsn){
	Statement statement = null;
	ResultSet rs = null;
	String author = null;
	try{
	    statement = connection.createStatement();
	    String sql = "select strippedauthor.shortauthor from strippedauthor, taxonomic_units where "
		+ " taxonomic_units.tsn=" + tsn
		+ " and taxonomic_units.taxon_author_id = strippedauthor.taxon_author_id"
		+ ";";
	    System.out.println(sql);
	    rs = statement.executeQuery(sql);
	    if(!rs.isBeforeFirst()){
		return null;
	    }
	    if(!rs.next()){
		return null;
	    }
	    //author = rs.getString("strippedauthor.shortauthor");
	    author = rs.getString("shortauthor");
	}
	catch(Exception e){
	    e.printStackTrace();
	}
	finally{
	    closeAll(statement, rs);
	}
	return author;
    }


    static void closeAll(final Statement s, final ResultSet r){
	if(s != null){
	    try{
		s.close();
	    }catch(Exception e){
		e.printStackTrace();
	    }finally{
		if(r != null){
		    try{
			r.close();
		    }catch(Exception e){
			e.printStackTrace();
		    }	
		}
	    }
	}
    }

    static final TaxonAuthor makeTaxonAuthor(final Connection conn, final String taxonAuthorId) throws SQLException{
	Statement statement = connection.createStatement();
	TaxonAuthor ta = new TaxonAuthor();
	String sql = "select * from taxon_authors_lkp where taxon_author_id=" + taxonAuthorId;
	ResultSet rs = statement.executeQuery(sql);

	while(rs.next())
	    {
		ta.authorship = rs.getString("taxon_author");
	    }
	closeAll(statement, rs);

	return ta;
    }

    static final void makeRefs(final FullRecord rec, final Connection conn, final String tsn) throws SQLException{
	Statement statement = connection.createStatement();
	ResultSet rs = statement.executeQuery("select * from reference_links where tsn=" + tsn);
	while(rs.next())
	    {
		String prefix = rs.getString("doc_id_prefix");
		//System.out.println("\t\t----- " + prefix);
		String docId = rs.getString("documentation_id");
		//System.out.println("\tdocumentation_id = " + docId);
		
		if(prefix.equals(DOC_TYPE_PUB)){
		    //System.out.println("\tvernacular_name = " + rs.getString("vernacular_name"));
		    rec.publicationList = makePublications(connection, docId);
		}else 
		    if(prefix.equals(DOC_TYPE_SRC)){
			rec.otherSourceList = makeOtherSources(connection, docId);
		    }else
			if(prefix.equals(DOC_TYPE_EXP)){
			    rec.expertList = makeExperts(connection, docId);
			}
	    }
	closeAll(statement, rs);
    }

    static final PublicationList makePublications(final Connection conn, final String docId) throws SQLException{
	PublicationList publicationList = new PublicationList();

	Statement statement = connection.createStatement();
	String sql = "select * from publications where publication_id=" + docId;
	ResultSet rs = statement.executeQuery(sql);

	while(rs.next())
	    {
		Publication pub = new Publication();
		pub.actualPubDate = rs.getString("actual_pub_date");
		pub.isbn = rs.getString("isbn");
		pub.issn = rs.getString("issn");
		pub.listedPubDate = rs.getString("listed_pub_date");
		pub.pages = rs.getString("pages");
		pub.pubComment = rs.getString("pub_comment");
		pub.pubName = rs.getString("publication_name");
		pub.pubPlace = rs.getString("pub_place");
		pub.publisher = rs.getString("publisher");
		pub.referenceAuthor = rs.getString("reference_author");
		pub.title = rs.getString("title");
		
		if(publicationList.publications == null){
		    publicationList.publications = new ArrayList<Publication>();
		}
		publicationList.publications.add(pub);
		
		//System.out.println("\t\ttitle = " + rs.getString("title"));
		//System.out.println("\t\tpublication_name = " + rs.getString("publication_name"));
	    }
	return publicationList;
    }

    static final ExpertList makeExperts(final Connection conn, final String docId) throws SQLException{
	Statement statement = connection.createStatement();
	String sql = "select * from experts where expert_id=" + docId;
	ResultSet rs = statement.executeQuery(sql);
	ExpertList expertList = new ExpertList();
	while(rs.next())
	    {
		//System.out.println("\t\texpert = " + rs.getString("expert"));
		//System.out.println("\t\texpert_id_prefix = " + rs.getString("expert_id_prefix"));
		Expert exp = new Expert();
		exp.expert = rs.getString("expert");
		exp.comment = rs.getString("exp_comment");
		exp.updateDate = rs.getString("update_date");
		if(expertList.experts == null){
		    expertList.experts = new ArrayList<Expert>();
		}
		expertList.experts.add(exp);
	    }
	return expertList;
    }

    static final OtherSourceList makeOtherSources(final Connection conn, final String docId) throws SQLException{
	OtherSourceList otherSourceList = new OtherSourceList();
	otherSourceList.otherSources = new ArrayList<OtherSource>();

	Statement statement = connection.createStatement();
	String sql = "select * from other_sources where source_id=" + docId;

	ResultSet rs = statement.executeQuery(sql);

	while(rs.next())
	    {
		OtherSource oso = new OtherSource();
		oso.source = rs.getString("source");
		oso.sourceComment = rs.getString("source_comment");
		oso.sourceType = rs.getString("source_type");
		oso.version = rs.getString("version");
		otherSourceList.otherSources.add(oso);
	    }
	
	closeAll(statement, rs);
	return otherSourceList;
	
    }

    static final AcceptedNamesList makeAcceptedNames(final Connection conn, final String tsn) throws SQLException{
	AcceptedNamesList anl = new AcceptedNamesList();
	anl.acceptedNames = new ArrayList<AcceptedName>();
	
	Statement statement = connection.createStatement();
	String sql = "select taxonomic_units.tsn, taxonomic_units.complete_name, strippedauthor.shortauthor from strippedauthor, synonym_links, taxonomic_units where synonym_links.tsn="
	    + tsn
	    + " and taxonomic_units.tsn=synonym_links.tsn_accepted" 
	    + " and taxonomic_units.taxon_author_id = strippedauthor.taxon_author_id"
	    + ";";
	
	ResultSet rs = statement.executeQuery(sql);
	while(rs.next())
	    {
		AcceptedName an = new AcceptedName();
		an.acceptedName = rs.getString("complete_name");
		an.acceptedTsn =  rs.getString("tsn");
		an.author =  rs.getString("shortauthor");
		anl.acceptedNames.add(an);
	    }
	closeAll(statement, rs);

	return anl;
    }

    static final CommonNamesList makeCommonNames(final Connection conn, final String tsn) throws SQLException{
	CommonNamesList cnl = new CommonNamesList();
	cnl.commonNames = new ArrayList<CommonName>();

	Statement statement = connection.createStatement();
	String sql = "select * from vernaculars where tsn=" + tsn;
	System.out.println(sql);
	ResultSet rs = statement.executeQuery(sql);
	while(rs.next())
	    {
		CommonName cn = new CommonName();
		cn.commonName = rs.getString("vernacular_name");
		cn.language = rs.getString("language");
		System.out.println(cn.commonName + ":" + cn.language);
		cnl.commonNames.add(cn);
	    }
	closeAll(statement, rs);
	return cnl;
    }

    static final GeographicDivisionsList makeGeographicDivisions(final Connection conn, final String tsn) throws SQLException{
	GeographicDivisionsList gdl = new GeographicDivisionsList();
	gdl.geoDivisions = new ArrayList<GeoDivision>();
	
	Statement statement = connection.createStatement();
	ResultSet rs = statement.executeQuery("select * from geographic_div where tsn=" + tsn);
	while(rs.next())
	    {
		GeoDivision gd = new GeoDivision();
		gd.geographicValue = rs.getString("geographic_value");
		gdl.geoDivisions.add(gd);
	    }
	closeAll(statement, rs);
	return gdl;
    }

    static final SynonymList makeSynonyms(final Connection conn, final String tsn) throws SQLException{
	SynonymList snl = new SynonymList();
	snl.synonyms = new ArrayList<Synonym>();
	
	Statement statement = connection.createStatement();
	String sql = "select taxonomic_units.tsn, complete_name, strippedauthor.shortauthor from strippedauthor, synonym_links, taxonomic_units where synonym_links.tsn_accepted=" 
	    + tsn 
	    + " and taxonomic_units.tsn=synonym_links.tsn and taxonomic_units.taxon_author_id = strippedauthor.taxon_author_id;";
	
	ResultSet rs = statement.executeQuery(sql);
	while(rs.next())
	    {
		Synonym syn = new Synonym();
		syn.sciName = rs.getString("complete_name");
		syn.tsn =  rs.getString("tsn");
		syn.author =  rs.getString("shortauthor");
		snl.synonyms.add(syn);
	    }
	closeAll(statement, rs);

	return snl;
    }

    static final CommentList makeComments(final Connection conn, final String tsn) throws SQLException{
	Statement statement = connection.createStatement();
	String sql = "select comments.comment_id, comments.commentator, comments.comment_detail  "
	    + " from tu_comments_links, comments where tsn=" + tsn 
	    + " and tu_comments_links.comment_id = comments.comment_id";
	
	ResultSet rs = statement.executeQuery(sql);
	CommentList commentList = new CommentList();
	
	commentList.comments = new ArrayList<Comment>();
	while(rs.next())
	    {
		Comment comment = new Comment();
		commentList.comments.add(comment);
		comment.commentId = rs.getString("comment_id");
		comment.commentDetail = rs.getString("comment_detail");
		comment.commentator = rs.getString("commentator");
	    }
	closeAll(statement, rs);
	return commentList;
    }

    static final JurisdictionalOriginsList makeJurisdictionalOrigins(final Connection conn, final String tsn){
	JurisdictionalOriginsList jurisdictionalOriginsList = new JurisdictionalOriginsList();
	jurisdictionalOriginsList.jurisdictionalOrigins = new ArrayList<JurisdictionalOrigin>();
	
	Statement statement = null;
	ResultSet rs = null;
	try{
	    statement = connection.createStatement();
	    rs = statement.executeQuery("select * from jurisdiction where tsn=" + tsn);
	    while(rs.next())
		{
		    //System.out.println("\tjurisdiction_value = " + rs.getString("jurisdiction_value") + "    tsn=" + tsn);
		    //System.out.println("\torigin = " + rs.getString("origin"));
		    JurisdictionalOrigin jurisdictionalOrigin = new JurisdictionalOrigin();
		    jurisdictionalOrigin.jurisdictionValue = rs.getString("jurisdiction_value");
		    jurisdictionalOrigin.origin = rs.getString("origin");
		    jurisdictionalOriginsList.jurisdictionalOrigins.add(jurisdictionalOrigin);
		}
	    closeAll(statement, rs);
	}catch(SQLException e){
	    e.printStackTrace();
	}finally{
	    closeAll(statement, rs);
	}
	
	return jurisdictionalOriginsList;
    }

}//
