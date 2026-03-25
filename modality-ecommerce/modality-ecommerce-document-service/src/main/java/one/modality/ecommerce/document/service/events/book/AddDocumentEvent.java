package one.modality.ecommerce.document.service.events.book;

import dev.webfx.stack.orm.entity.Entities;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.Person;
import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;

import java.time.Instant;

/**
 * @author Bruno Salmon
 */
public final class AddDocumentEvent extends AbstractDocumentEvent {

    private final Object eventPrimaryKey;
    private final boolean inPerson;
    private String personLang;
    // Booking with an account
    private Person person; // not serialized
    private Object personPrimaryKey; // serialized
    // Booking as a guest - or loaded by server
    private String firstName;
    private String lastName;
    private String email;
    // Additional fields loaded by server only
    private final Integer age;
    private Integer ref;
    private final Instant creationDate;

    public AddDocumentEvent(Document document) {
        super(document);
        eventPrimaryKey = Entities.getPrimaryKey(document.getEvent());
        inPerson = document.isInPerson();
        personLang = document.getPersonLang();
        personPrimaryKey = Entities.getPrimaryKey(document.getPerson());
        firstName = document.getFirstName();
        lastName = document.getLastName();
        email = document.getEmail();
        age = document.getAge();
        ref = document.getRef();
        creationDate = document.isNew() ? Instant.now() : document.getCreationDate();
    }

    public AddDocumentEvent(Object documentPrimaryKey, Object eventPrimaryKey, boolean inPerson, String personLang, Object personPrimaryKey, String firstName, String lastName, String email, Integer age, Integer ref, Instant creationDate) {
        super(documentPrimaryKey);
        this.eventPrimaryKey = eventPrimaryKey;
        this.inPerson = inPerson;
        this.personLang = personLang;
        this.personPrimaryKey = personPrimaryKey;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.age = age;
        this.ref = ref;
        this.creationDate = creationDate;
    }

    public Object getEventPrimaryKey() {
        return eventPrimaryKey;
    }

    public boolean isInPerson() {
        return inPerson;
    }

    public String getPersonLang() {
        return personLang;
    }

    public void setPersonLang(String personLang) {
        this.personLang = personLang;
    }

    public Person getPerson() {
        return person;
    }

    public void setPerson(Person person) {
        this.person = person;
        setPersonPrimaryKey(Entities.getPrimaryKey(person));
    }

    public Object getPersonPrimaryKey() {
        return personPrimaryKey;
    }

    public void setPersonPrimaryKey(Object personPrimaryKey) {
        this.personPrimaryKey = personPrimaryKey;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Integer getAge() {
        return age;
    }

    public Integer getRef() {
        return ref;
    }

    public void setRef(Integer ref) {
        this.ref = ref;
    }

    public Instant getCreationDate() {
        return creationDate;
    }

    @Override
    protected void createDocument() {
        if (isForSubmit()) {
            document = updateStore.insertEntity(Document.class, getDocumentPrimaryKey());
            document.setFieldValue("activity", 12); // GP Class TODO: remove activity from DB
        } else {
            super.createDocument();
        }
    }

    @Override
    public void replayEventOnDocument() {
        super.replayEventOnDocument();
        document.setEvent(isForSubmit() ? getEventPrimaryKey() : entityStore.getOrCreateEntity(Event.class, getEventPrimaryKey()));
        document.setInPerson(inPerson);
        document.setPersonLang(personLang);
        if (person != null)
            document.setPerson(person);
        else if (personPrimaryKey != null)
            document.setPerson(isForSubmit() ? personPrimaryKey : entityStore.getOrCreateEntity(Person.class, personPrimaryKey));
        document.setFirstName(firstName);
        document.setLastName(lastName);
        document.setEmail(email);
        document.setAge(age);
        if (ref != null)
            document.setRef(ref);
        if (creationDate != null)
            document.setCreationDate(creationDate);
    }
}
