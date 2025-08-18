import com.fasterxml.jackson.annotation.JsonAnyGetter;
import jakarta.persistence.*;

@Entity @Table(name="teams")
public class Team {
    @Id @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long teamId;
    @Column(nullable = false, unique=true)
    private String teamName;
    private String teamAbbr;
}
