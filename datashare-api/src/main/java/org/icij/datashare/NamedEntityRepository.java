package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;

import java.sql.SQLException;
import java.util.List;

interface NamedEntityRepository {
   Document get(String id);
   void create(List<NamedEntity> neList);
   void create(Document document) throws SQLException;
   void update(NamedEntity ne);
    NamedEntity delete(String id);
}
