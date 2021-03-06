/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.httprpc.demo;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.httprpc.RPC;
import org.httprpc.WebService;

/**
 * Simple note management service demo.
 */
public class NoteService extends WebService {
    private static LinkedHashMap<Integer, Map<String, ?>> notes = new LinkedHashMap<>();

    private static int nextNoteID = 1;

    private static final String ID_KEY = "id";
    private static final String DATE_KEY = "date";
    private static final String MESSAGE_KEY = "message";

    /**
     * Adds a note.
     *
     * @param message
     * The note text.
     */
    @RPC(method="POST")
    public void addNote(String message) {
        synchronized (notes) {
            notes.put(nextNoteID, mapOf(
                entry(ID_KEY, nextNoteID),
                entry(DATE_KEY, new Date().getTime()),
                entry(MESSAGE_KEY, message))
            );

            nextNoteID++;
        }
    }

    /**
     * Removes a note.
     *
     * @param id
     * The note ID.
     */
    @RPC(method="DELETE")
    public void deleteNote(int id) {
        synchronized (notes) {
            notes.remove(id);
        }
    }

    /**
     * Retrieves a list of all notes.
     *
     * @return
     * A list of all notes.
     */
    @RPC(method="GET")
    public List<Map<String, ?>> getNotes() {
        LinkedList<Map<String, ?>> noteList = new LinkedList<>();

        synchronized (notes) {
            for (Map<String, ?> note : notes.values()) {
                noteList.add(mapOf(
                    entry(ID_KEY, note.get(ID_KEY)),
                    entry(DATE_KEY, note.get(DATE_KEY)),
                    entry(MESSAGE_KEY, note.get(MESSAGE_KEY))
                ));
            }
        }

        return noteList;
    }
}
